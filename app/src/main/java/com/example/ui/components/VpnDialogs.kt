package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import com.example.model.VpnServerNode
import com.example.ui.theme.AmberConnecting
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.CyanUpload
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianCard
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.vpn.LaunchableAppInfo
import com.example.vpn.NetworkDiagnosticsResult

@Composable
fun AddServerDialog(
    onImportUri: (String) -> Int,
    onAddManual: (
        label: String,
        host: String,
        port: Int,
        uuidOrPassword: String,
        sni: String,
        publicKey: String,
        shortId: String,
        isHysteria2: Boolean
    ) -> Unit,
    onDismiss: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    var modeIndex by remember { mutableIntStateOf(0) } // 0 = URI, 1 = Manual
    var uriText by remember { mutableStateOf("") }
    var uriError by remember { mutableStateOf<String?>(null) }

    var label by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("443") }
    var isHysteria2 by remember { mutableStateOf(false) }
    var secret by remember { mutableStateOf("") }
    var sni by remember { mutableStateOf("www.microsoft.com") }
    var publicKey by remember { mutableStateOf("") }
    var shortId by remember { mutableStateOf("") }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = EmeraldAccent,
        unfocusedBorderColor = ObsidianBorder,
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        focusedLabelColor = EmeraldAccent,
        unfocusedLabelColor = TextSecondary,
        cursorColor = EmeraldAccent
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = ObsidianSurface,
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, ObsidianBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Добавить сервер",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDismiss()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Закрыть",
                            tint = TextSecondary
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Ссылка / Base64", "Вручную").forEachIndexed { idx, title ->
                        val selected = modeIndex == idx
                        FilterChip(
                            selected = selected,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                modeIndex = idx
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
                                selected = selected,
                                borderColor = ObsidianBorder,
                                selectedBorderColor = EmeraldAccent.copy(alpha = 0.6f)
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (modeIndex == 0) {
                    OutlinedTextField(
                        value = uriText,
                        onValueChange = {
                            uriText = it
                            uriError = null
                        },
                        label = { Text("vless://, hy2:// или Base64") },
                        placeholder = { Text("vless://uuid@ip:443?security=reality...") },
                        minLines = 3,
                        maxLines = 5,
                        colors = fieldColors,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("uri_import_input")
                    )
                    if (uriError != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = uriError.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = CoralAlert
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            val clip = clipboardManager.getText()?.text.orEmpty()
                            if (clip.isNotBlank()) {
                                uriText = clip
                                uriError = null
                            }
                        },
                        border = BorderStroke(1.dp, ObsidianBorder),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentPaste,
                            contentDescription = null,
                            tint = EmeraldAccent,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Вставить из буфера", color = TextPrimary)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            val added = onImportUri(uriText)
                            if (added > 0) {
                                onDismiss()
                            } else {
                                uriError = "Не найдено валидных ссылок vless:// или hy2://"
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EmeraldAccent,
                            contentColor = ObsidianBg
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("confirm_uri_import_button")
                    ) {
                        Text("Импортировать", style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !isHysteria2,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                isHysteria2 = false
                                if (sni == "bing.com") sni = "www.microsoft.com"
                            },
                            label = { Text("VLESS Reality") },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = ObsidianCard,
                                labelColor = TextSecondary,
                                selectedContainerColor = EmeraldAccent.copy(alpha = 0.16f),
                                selectedLabelColor = EmeraldAccent
                            )
                        )
                        FilterChip(
                            selected = isHysteria2,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                isHysteria2 = true
                                if (sni == "www.microsoft.com") sni = "bing.com"
                            },
                            label = { Text("Hysteria 2") },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = ObsidianCard,
                                labelColor = TextSecondary,
                                selectedContainerColor = EmeraldAccent.copy(alpha = 0.16f),
                                selectedLabelColor = EmeraldAccent
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Название (например, DE · VPS)") },
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it },
                            label = { Text("IP / Домен") },
                            singleLine = true,
                            colors = fieldColors,
                            modifier = Modifier.weight(2f)
                        )
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                            label = { Text("Порт") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = fieldColors,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it },
                        label = { Text(if (isHysteria2) "Пароль Hysteria 2" else "UUID клиента") },
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = sni,
                        onValueChange = { sni = it },
                        label = { Text("SNI (маскировка TLS)") },
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (!isHysteria2) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = publicKey,
                            onValueChange = { publicKey = it },
                            label = { Text("Reality Public Key (pbk)") },
                            singleLine = true,
                            colors = fieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = shortId,
                            onValueChange = { shortId = it },
                            label = { Text("Short ID (sid)") },
                            singleLine = true,
                            colors = fieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        enabled = host.isNotBlank(),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onAddManual(
                                label,
                                host,
                                portText.toIntOrNull() ?: 443,
                                secret,
                                sni,
                                publicKey,
                                shortId,
                                isHysteria2
                            )
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EmeraldAccent,
                            contentColor = ObsidianBg
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Сохранить сервер", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSplitTunnelSheet(
    apps: List<LaunchableAppInfo>,
    excludedPackages: Set<String>,
    isLoading: Boolean,
    onToggleApp: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var query by remember { mutableStateOf("") }
    val filteredApps = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ObsidianSurface,
        contentColor = TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
        ) {
            Text(
                text = "Приложения в обход VPN",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Отмеченные приложения идут напрямую (addDisallowedApplication)",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Поиск приложения…") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = TextSecondary
                    )
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = EmeraldAccent,
                    unfocusedBorderColor = ObsidianBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = EmeraldAccent)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        val excluded = excludedPackages.contains(app.packageName)
                        val iconBitmap = remember(app.packageName) {
                            runCatching {
                                app.icon?.toBitmap(width = 72, height = 72)?.asImageBitmap()
                            }.getOrNull()
                        }
                        Surface(
                            color = if (excluded) EmeraldAccent.copy(alpha = 0.08f) else ObsidianCard,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(
                                1.dp,
                                if (excluded) EmeraldAccent.copy(alpha = 0.5f) else ObsidianBorder
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onToggleApp(app.packageName)
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (iconBitmap != null) {
                                    Image(
                                        bitmap = iconBitmap,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Apps,
                                        contentDescription = null,
                                        tint = TextSecondary,
                                        modifier = Modifier.size(34.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = app.label,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = app.packageName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted
                                    )
                                }
                                Checkbox(
                                    checked = excluded,
                                    onCheckedChange = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onToggleApp(app.packageName)
                                    },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = EmeraldAccent,
                                        checkmarkColor = ObsidianBg,
                                        uncheckedColor = TextSecondary
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkDiagnosticsSheet(
    node: VpnServerNode,
    isRunning: Boolean,
    result: NetworkDiagnosticsResult?,
    onRunDiagnostics: () -> Unit,
    onDismiss: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ObsidianSurface,
        contentColor = TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Диагностика сети и DoH",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = "Проверка внешнего IP, DNS-over-HTTPS и ноды",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp,
                        color = EmeraldAccent
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                color = ObsidianCard,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, ObsidianBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    DiagnosticRow(
                        label = "Внешний IPv4",
                        value = result?.publicIp ?: if (isRunning) "Запрос…" else "—",
                        accent = if (result?.publicIp != null) EmeraldAccent else TextSecondary
                    )
                    HorizontalDivider(
                        color = ObsidianBorder,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                    DiagnosticRow(
                        label = "Страна / Edge PoP",
                        value = listOfNotNull(result?.countryCode, result?.edgeColocation)
                            .joinToString(" · ")
                            .ifEmpty { if (isRunning) "Определение…" else "—" },
                        accent = CyanUpload
                    )
                    HorizontalDivider(
                        color = ObsidianBorder,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                    DiagnosticRow(
                        label = "DoH 1.1.1.1 (DNS)",
                        value = when {
                            isRunning -> "Проверка…"
                            result == null -> "—"
                            result.dohReachable -> "Защищён (${result.dohLatencyMs ?: 0} ms)"
                            else -> "Недоступен"
                        },
                        accent = if (result?.dohReachable == true) EmeraldAccent else AmberConnecting
                    )
                    HorizontalDivider(
                        color = ObsidianBorder,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                    DiagnosticRow(
                        label = "TCP нода (${node.endpointHost}:${node.port})",
                        value = when {
                            isRunning -> "Сокет…"
                            result?.nodeTcpReachable == null -> "Демо-хост (TEST-NET)"
                            result.nodeTcpReachable -> "Доступна (${result.nodeTcpLatencyMs ?: 0} ms)"
                            else -> "Таймаут / блокировка"
                        },
                        accent = when (result?.nodeTcpReachable) {
                            true -> EmeraldAccent
                            false -> CoralAlert
                            null -> TextSecondary
                        }
                    )
                }
            }

            result?.error?.let { err ->
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = CoralAlert,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodyMedium,
                        color = CoralAlert
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                enabled = !isRunning,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onRunDiagnostics()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmeraldAccent,
                    contentColor = ObsidianBg
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rerun_diagnostics_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isRunning) "Выполняется проверка…" else "Проверить снова",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
    accent: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = accent
        )
    }
}
