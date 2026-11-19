package com.example

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.VpnConnectionState
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.RoutingScreen
import com.example.ui.screens.ServersScreen
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ShieldBoxTheme
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.VpnViewModel

private enum class AppTab(
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val tag: String
) {
    HOME(R.string.tab_home, Icons.Rounded.Shield, Icons.Outlined.Shield, "nav_home"),
    SERVERS(R.string.tab_servers, Icons.Rounded.Public, Icons.Outlined.Public, "nav_servers"),
    ROUTING(R.string.tab_routing, Icons.Rounded.Tune, Icons.Outlined.Tune, "nav_routing")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(ObsidianBg.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(ObsidianSurface.toArgb())
        )
        setContent {
            ShieldBoxTheme {
                ShieldBoxRootApp()
            }
        }
    }
}

@Composable
private fun ShieldBoxRootApp(vpnViewModel: VpnViewModel = viewModel()) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by vpnViewModel.uiState.collectAsStateWithLifecycle()
    var activeTab by rememberSaveable { mutableStateOf(AppTab.HOME) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vpnViewModel.refreshBatteryOptimizationStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val vpnPrepareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            vpnViewModel.startVpnService()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            vpnPrepareLauncher.launch(prepareIntent)
        } else {
            vpnViewModel.startVpnService()
        }
    }

    fun requestVpnStart() {
        if (uiState.telemetry.connectionState != VpnConnectionState.DISCONNECTED) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            vpnPrepareLauncher.launch(prepareIntent)
        } else {
            vpnViewModel.startVpnService()
        }
    }

    fun requestVpnToggle() {
        if (uiState.telemetry.connectionState != VpnConnectionState.DISCONNECTED) {
            vpnViewModel.stopVpnService()
        } else {
            requestVpnStart()
        }
    }

    BackHandler(enabled = activeTab != AppTab.HOME) {
        activeTab = AppTab.HOME
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 600.dp

        Scaffold(
            containerColor = ObsidianBg,
            bottomBar = {
                if (!useNavigationRail) {
                    NavigationBar(
                        containerColor = ObsidianSurface,
                        tonalElevation = 0.dp
                    ) {
                        AppTab.entries.forEach { tab ->
                            val selected = activeTab == tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    activeTab = tab
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = stringResource(tab.labelRes)
                                    )
                                },
                                label = { Text(stringResource(tab.labelRes)) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = EmeraldAccent,
                                    selectedTextColor = EmeraldAccent,
                                    indicatorColor = EmeraldAccent.copy(alpha = 0.14f),
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                ),
                                modifier = Modifier.testTag(tab.tag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavigationRail) {
                    NavigationRail(
                        containerColor = ObsidianSurface,
                        contentColor = Color.White
                    ) {
                        AppTab.entries.forEach { tab ->
                            val selected = activeTab == tab
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    activeTab = tab
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = stringResource(tab.labelRes)
                                    )
                                },
                                label = { Text(stringResource(tab.labelRes)) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = EmeraldAccent,
                                    selectedTextColor = EmeraldAccent,
                                    indicatorColor = EmeraldAccent.copy(alpha = 0.14f),
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                ),
                                modifier = Modifier.testTag(tab.tag)
                            )
                        }
                    }
                }

                when (activeTab) {
                    AppTab.HOME -> HomeScreen(
                        uiState = uiState,
                        configJsonPreview = vpnViewModel.generatedConfigJson(),
                        onToggleConnection = { requestVpnToggle() },
                        onSelectNode = { vpnViewModel.selectNode(it) },
                        onAutoConnect = {
                            if (vpnViewModel.consumeAutoConnectIfNeeded()) {
                                requestVpnStart()
                            }
                        },
                        onRunDiagnostics = { vpnViewModel.runNetworkDiagnostics() },
                        onRunPreConnectTest = { vpnViewModel.measureAllServerPings() },
                        onSelectFastestNode = { vpnViewModel.selectFastestNode() }
                    )

                    AppTab.SERVERS -> ServersScreen(
                        uiState = uiState,
                        onSelectNode = {
                            vpnViewModel.selectNode(it)
                            activeTab = AppTab.HOME
                        },
                        onMeasurePings = { vpnViewModel.measureAllServerPings() },
                        onImportUri = { vpnViewModel.importNodesFromUri(it) },
                        onAddManualNode = { label, host, port, secret, sni, pbk, sid, isHy2 ->
                            vpnViewModel.addManualNode(
                                label = label,
                                host = host,
                                port = port,
                                uuidOrPassword = secret,
                                sni = sni,
                                publicKey = pbk,
                                shortId = sid,
                                isHysteria2 = isHy2
                            )
                        },
                        onDeleteCustomNode = { vpnViewModel.deleteCustomNode(it) },
                        onClearImportFeedback = { vpnViewModel.clearImportFeedback() }
                    )

                    AppTab.ROUTING -> RoutingScreen(
                        uiState = uiState,
                        configJsonPreview = vpnViewModel.generatedConfigJson(),
                        onSelectProtocol = { vpnViewModel.selectProtocolMode(it) },
                        onUpdateRouting = { vpnViewModel.updateRouting(it) },
                        onSelectMtu = { vpnViewModel.setMtu(it) },
                        onToggleConnectOnLaunch = { vpnViewModel.setConnectOnAppLaunch(it) },
                        onLoadInstalledApps = { vpnViewModel.loadInstalledAppsIfNeeded() },
                        onToggleExcludedApp = { vpnViewModel.toggleExcludedApp(it) }
                    )
                }
            }
        }
    }
}
