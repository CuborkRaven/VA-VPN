package com.example.ui.components

import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.model.VpnConnectionState
import com.example.model.VpnServerNode
import com.example.ui.theme.AmberConnecting
import com.example.ui.theme.CyanUpload
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianCard
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun AnimatedConnectOrb(
    connectionState: VpnConnectionState,
    connectedSeconds: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent by animateColorAsState(
        targetValue = when (connectionState) {
            VpnConnectionState.CONNECTED -> EmeraldAccent
            VpnConnectionState.CONNECTING -> AmberConnecting
            VpnConnectionState.DISCONNECTED -> TextMuted
        },
        animationSpec = tween(durationMillis = 350),
        label = "orb_accent"
    )

    val buttonScale by animateFloatAsState(
        targetValue = if (connectionState == VpnConnectionState.CONNECTING) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.6f),
        label = "orb_scale"
    )

    val haptic = LocalHapticFeedback.current
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_progress"
    )
    val spinDegrees by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sync_spin"
    )

    val isActive = connectionState != VpnConnectionState.DISCONNECTED

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(236.dp)
    ) {
        // Radar / concentric pulse rings on Canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = 86.dp.toPx()

            drawCircle(
                color = ObsidianBorder.copy(alpha = 0.55f),
                radius = baseRadius + 24.dp.toPx(),
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )

            if (isActive) {
                val ring1Radius = baseRadius + (pulseProgress * 32.dp.toPx())
                val ring1Alpha = (1f - pulseProgress) * 0.55f
                drawCircle(
                    color = accent.copy(alpha = ring1Alpha),
                    radius = ring1Radius,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )

                val p2 = (pulseProgress + 0.5f) % 1f
                val ring2Radius = baseRadius + (p2 * 32.dp.toPx())
                val ring2Alpha = (1f - p2) * 0.35f
                drawCircle(
                    color = accent.copy(alpha = ring2Alpha),
                    radius = ring2Radius,
                    center = center,
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }

        // Main interactive circular core button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(172.dp)
                .scale(buttonScale)
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accent.copy(alpha = if (connectionState == VpnConnectionState.CONNECTED) 0.24f else 0.12f),
                            ObsidianCard
                        )
                    )
                )
                .border(
                    width = 2.5.dp,
                    color = accent.copy(alpha = if (isActive) 0.9f else 0.45f),
                    shape = CircleShape
                )
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                }
                .minimumInteractiveComponentSize()
                .testTag("connect_button")
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = if (connectionState == VpnConnectionState.CONNECTING) {
                        Icons.Rounded.Sync
                    } else {
                        Icons.Rounded.PowerSettingsNew
                    },
                    contentDescription = stringResource(R.string.cd_connect_button),
                    tint = if (connectionState == VpnConnectionState.CONNECTED) accent else TextPrimary,
                    modifier = Modifier
                        .size(48.dp)
                        .rotate(if (connectionState == VpnConnectionState.CONNECTING) spinDegrees else 0f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = when (connectionState) {
                        VpnConnectionState.CONNECTED -> formatDuration(connectedSeconds)
                        VpnConnectionState.CONNECTING -> "СОЕДИНЕНИЕ"
                        VpnConnectionState.DISCONNECTED -> "ПОДКЛЮЧИТЬ"
                    },
                    style = MaterialTheme.typography.labelMedium.copy(
                        letterSpacing = 1.4.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = if (connectionState == VpnConnectionState.CONNECTED) accent else TextPrimary
                )
            }
        }
    }
}

@Composable
fun ThroughputSparklineCard(
    downloadHistory: List<Float>,
    uploadHistory: List<Float>,
    modifier: Modifier = Modifier
) {
    Surface(
        color = ObsidianCard,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, ObsidianBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "АКТИВНОСТЬ ТУННЕЛЯ",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                    color = TextSecondary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LegendDot(color = EmeraldAccent, label = "RX")
                    LegendDot(color = CyanUpload, label = "TX")
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                val maxVal = maxOf(
                    downloadHistory.maxOrNull() ?: 1f,
                    uploadHistory.maxOrNull() ?: 1f,
                    12f
                )

                fun buildLinePath(values: List<Float>): Path {
                    val path = Path()
                    if (values.size < 2) return path
                    val stepX = size.width / (values.size - 1).coerceAtLeast(1)
                    values.forEachIndexed { idx, value ->
                        val x = idx * stepX
                        val norm = (value / maxVal).coerceIn(0f, 1f)
                        val y = size.height - (norm * (size.height - 6.dp.toPx())) - 3.dp.toPx()
                        if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    return path
                }

                // Baseline grid line
                drawLine(
                    color = ObsidianBorder.copy(alpha = 0.6f),
                    start = Offset(0f, size.height - 3.dp.toPx()),
                    end = Offset(size.width, size.height - 3.dp.toPx()),
                    strokeWidth = 1.dp.toPx()
                )

                drawPath(
                    path = buildLinePath(uploadHistory),
                    color = CyanUpload.copy(alpha = 0.85f),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )
                drawPath(
                    path = buildLinePath(downloadHistory),
                    color = EmeraldAccent,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountryPickerBottomSheet(
    nodes: List<VpnServerNode>,
    selectedNodeId: String,
    onSelect: (String) -> Unit,
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
            Text(
                text = "Выбор локации",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Автопереключение: Reality → Hysteria 2 → CDN",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(16.dp))

            nodes.forEach { node ->
                val isSelected = node.id == selectedNodeId
                val ping = node.measuredPingMs ?: node.defaultPingMs
                val bwText = node.measuredBandwidthMbps?.let { String.format("%.1f Mbps", it) }
                Surface(
                    color = if (isSelected) EmeraldAccent.copy(alpha = 0.1f) else ObsidianCard,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) EmeraldAccent.copy(alpha = 0.6f) else ObsidianBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(node.id)
                            onDismiss()
                        }
                        .testTag("sheet_node_${node.countryCode.lowercase()}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = node.flagEmoji, fontSize = 24.sp)
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${node.countryName} (${node.countryCode})",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = "${node.city} · нагрузка ${node.loadPercent}%",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "$ping ms",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (ping < 60) EmeraldAccent else AmberConnecting
                            )
                            if (bwText != null) {
                                Text(
                                    text = bwText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyanUpload
                                )
                            }
                        }
                        if (isSelected) {
                            Spacer(modifier = Modifier.width(10.dp))
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = EmeraldAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SingBoxConfigDialog(
    configJson: String,
    onDismiss: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var copied by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = ObsidianSurface,
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, ObsidianBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "sing-box config.json",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                    Row {
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "sing-box config.json")
                                    putExtra(Intent.EXTRA_TEXT, configJson)
                                }
                                context.startActivity(
                                    Intent.createChooser(sendIntent, "Экспорт sing-box config.json")
                                )
                            },
                            modifier = Modifier.testTag("share_config_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Share,
                                contentDescription = stringResource(R.string.cd_share_config),
                                tint = TextSecondary
                            )
                        }
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                clipboardManager.setText(AnnotatedString(configJson))
                                copied = true
                            },
                            modifier = Modifier.testTag("copy_config_button")
                        ) {
                            Icon(
                                imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                contentDescription = "Копировать JSON",
                                tint = EmeraldAccent
                            )
                        }
                    }
                }
                if (copied) {
                    Text(
                        text = "Скопировано в буфер обмена",
                        style = MaterialTheme.typography.labelSmall,
                        color = EmeraldAccent,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                HorizontalDivider(color = ObsidianBorder)
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ObsidianBg)
                        .border(1.dp, ObsidianBorder, RoundedCornerShape(12.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    Text(
                        text = configJson,
                        style = MaterialTheme.typography.labelSmall,
                        color = EmeraldAccent.copy(alpha = 0.9f)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDismiss()
                        }
                    ) {
                        Text("Закрыть", color = TextPrimary)
                    }
                }
            }
        }
    }
}

fun formatDuration(totalSeconds: Long): String {
    val hrs = totalSeconds / 3600
    val mins = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    return if (hrs > 0) {
        String.format("%02d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format("%02d:%02d", mins, secs)
    }
}

fun formatBytesPerSec(bytesPerSec: Long): String {
    if (bytesPerSec <= 0L) return "0.0 KB/s"
    val kb = bytesPerSec / 1024.0
    if (kb < 1024.0) return String.format("%.1f KB/s", kb)
    return String.format("%.2f MB/s", kb / 1024.0)
}

fun formatTotalBytes(bytes: Long): String {
    if (bytes <= 0L) return "0.0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    if (mb < 1024.0) return String.format("%.1f MB", mb)
    return String.format("%.2f GB", mb / 1024.0)
}
