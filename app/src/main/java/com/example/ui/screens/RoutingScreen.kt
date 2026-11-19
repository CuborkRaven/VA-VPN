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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.RoutingSettings
import com.example.model.VpnProtocolMode
import com.example.ui.components.AppSplitTunnelSheet
import com.example.ui.components.SingBoxConfigDialog
import com.example.ui.theme.AmberConnecting
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianCard
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.VpnAppUiState
import com.example.vpn.BatteryOptimizationHelper

@Composable
fun RoutingScreen(
    uiState: VpnAppUiState,
    configJsonPreview: String,
    onSelectProtocol: (VpnProtocolMode) -> Unit,
    onUpdateRouting: ((RoutingSettings) -> RoutingSettings) -> Unit,
    onSelectMtu: (Int) -> Unit = {},
    onToggleConnectOnLaunch: (Boolean) -> Unit = {},
    onLoadInstalledApps: () -> Unit = {},
    onToggleExcludedApp: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var showJsonDialog by remember { mutableStateOf(false) }
    var showAppSplitSheet by remember { mutableStateOf(false) }

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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Text(
                text = "Маршруты и ядро",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary
            )
            Text(
                text = "Протокол обхода DPI и Split Tunneling (GeoIP / GeoSite)",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "ТРАНСПОРТ И FALLBACK",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                color = ObsidianCard,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, ObsidianBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    VpnProtocolMode.entries.forEach { mode ->
                        val selected = uiState.protocolMode == mode
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelectProtocol(mode)
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                                .testTag("protocol_option_${mode.name.lowercase()}"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelectProtocol(mode)
                                },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = EmeraldAccent,
                                    unselectedColor = TextSecondary
                                )
                            )
                            Column(modifier = Modifier.padding(start = 6.dp)) {
                                Text(
                                    text = mode.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (selected) EmeraldAccent else TextPrimary
                                )
                                Text(
                                    text = mode.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "SPLIT TUNNELING И ЗАЩИТА",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                color = ObsidianCard,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, ObsidianBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    RoutingToggleRow(
                        title = "Банки и локальные сервисы напрямую",
                        subtitle = "Исключения geosite-category-ru и geoip-ru",
                        checked = uiState.routing.bypassDomesticRu,
                        onCheckedChange = { checked ->
                            onUpdateRouting { it.copy(bypassDomesticRu = checked) }
                        }
                    )
                    RoutingToggleRow(
                        title = "Приватные подсети (LAN) в обход",
                        subtitle = "Роутеры и локальные устройства (geosite-private)",
                        checked = uiState.routing.bypassPrivateNetworks,
                        onCheckedChange = { checked ->
                            onUpdateRouting { it.copy(bypassPrivateNetworks = checked) }
                        }
                    )
                    RoutingToggleRow(
                        title = "Защита от утечек DNS (DoH)",
                        subtitle = "Шифрованный DNS 1.1.1.1 + hijack-dns",
                        checked = uiState.routing.blockDnsLeaksDoH,
                        onCheckedChange = { checked ->
                            onUpdateRouting { it.copy(blockDnsLeaksDoH = checked) }
                        }
                    )
                    RoutingToggleRow(
                        title = "Strict Route (Kill Switch)",
                        subtitle = "Блокировка трафика вне TUN (MTU ${uiState.routing.mtu})",
                        checked = uiState.routing.strictKillSwitch,
                        onCheckedChange = { checked ->
                            onUpdateRouting { it.copy(strictKillSwitch = checked) }
                        }
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Размер пакета TUN (MTU)",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            1280 to "1280 · LTE",
                            1400 to "1400 · Реком.",
                            1500 to "1500 · Wi-Fi"
                        ).forEach { (mtuVal, label) ->
                            val selected = uiState.routing.mtu == mtuVal
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelectMtu(mtuVal)
                                },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = ObsidianBg,
                                    labelColor = TextSecondary,
                                    selectedContainerColor = EmeraldAccent.copy(alpha = 0.16f),
                                    selectedLabelColor = EmeraldAccent
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = selected,
                                    borderColor = ObsidianBorder,
                                    selectedBorderColor = EmeraldAccent.copy(alpha = 0.55f)
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Per-App Split Tunneling Picker Card
            val excludedCount = uiState.routing.excludedPackages.size
            Surface(
                color = ObsidianCard,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, ObsidianBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onLoadInstalledApps()
                        showAppSplitSheet = true
                    }
                    .testTag("app_split_tunnel_card")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Apps,
                        contentDescription = null,
                        tint = if (excludedCount > 0) EmeraldAccent else TextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Исключения приложений",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = if (excludedCount > 0) {
                                "В обход VPN выбрано приложений: $excludedCount"
                            } else {
                                "Все приложения идут через туннель ShieldBox"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (excludedCount > 0) EmeraldAccent else TextSecondary
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "ФОНОВАЯ РАБОТА И ЗАПУСК",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                color = ObsidianCard,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, ObsidianBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    RoutingToggleRow(
                        title = stringResource(R.string.setting_connect_on_launch_title),
                        subtitle = stringResource(R.string.setting_connect_on_launch_subtitle),
                        checked = uiState.connectOnAppLaunch,
                        onCheckedChange = onToggleConnectOnLaunch,
                        switchTestTag = "connect_on_launch_switch"
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val batteryIgnored = uiState.isBatteryOptimizationIgnored
            Surface(
                color = ObsidianCard,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(
                    1.dp,
                    if (batteryIgnored) EmeraldAccent.copy(alpha = 0.45f) else AmberConnecting.copy(alpha = 0.55f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (batteryIgnored) {
                                    Icons.Rounded.BatteryChargingFull
                                } else {
                                    Icons.Rounded.BatteryAlert
                                },
                                contentDescription = null,
                                tint = if (batteryIgnored) EmeraldAccent else AmberConnecting,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Оптимизация батареи",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary
                                )
                                Text(
                                    text = if (batteryIgnored) {
                                        "Фоновый туннель защищён от выгрузки ОС"
                                    } else {
                                        "Doze-режим может прерывать VPN в фоне"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (batteryIgnored) EmeraldAccent else AmberConnecting
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            BatteryOptimizationHelper.openBatteryOptimizationSettings(context)
                        },
                        border = BorderStroke(
                            1.dp,
                            if (batteryIgnored) ObsidianBorder else AmberConnecting.copy(alpha = 0.7f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("battery_optimization_button")
                    ) {
                        Text(
                            text = if (batteryIgnored) {
                                "Проверить настройки батареи"
                            } else {
                                "Отключить ограничения батареи"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = if (batteryIgnored) TextPrimary else AmberConnecting
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Surface(
                color = ObsidianCard,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, ObsidianBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Резервная доставка конфига (Fallback API)",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Primary: ${uiState.routing.primaryApiUrl}\nDoH TXT: ${uiState.routing.dohDiscoveryDomain}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            showJsonDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EmeraldAccent,
                            contentColor = ObsidianBg
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("preview_singbox_json_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Code,
                            contentDescription = null
                        )
                        Text(
                            text = "  Просмотр sing-box config.json",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showJsonDialog) {
        SingBoxConfigDialog(
            configJson = configJsonPreview,
            onDismiss = { showJsonDialog = false }
        )
    }

    if (showAppSplitSheet) {
        AppSplitTunnelSheet(
            apps = uiState.installedApps,
            excludedPackages = uiState.routing.excludedPackages,
            isLoading = uiState.isLoadingApps,
            onToggleApp = onToggleExcludedApp,
            onDismiss = { showAppSplitSheet = false }
        )
    }
}

@Composable
private fun RoutingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    switchTestTag: String? = null
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCheckedChange(!checked)
            }
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCheckedChange(it)
            },
            modifier = if (switchTestTag != null) Modifier.testTag(switchTestTag) else Modifier,
            colors = SwitchDefaults.colors(
                checkedThumbColor = ObsidianBg,
                checkedTrackColor = EmeraldAccent,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = ObsidianBg
            )
        )
    }
}
