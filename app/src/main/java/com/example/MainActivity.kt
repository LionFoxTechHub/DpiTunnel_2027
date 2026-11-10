package com.example

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.VpnStatus
import com.example.ui.AppTab
import com.example.ui.MainViewModel
import com.example.ui.screens.*
import com.example.ui.theme.*

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DpiTunnelTheme {
                val context = LocalContext.current
                val vpnState by viewModel.vpnState.collectAsState()
                val selectedProfile by viewModel.selectedProfile.collectAsState()
                val currentTab by viewModel.currentTab.collectAsState()

                // Activity Result Launcher for VPN Permission
                val vpnPrepareLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
                        viewModel.startVpn(context)
                    } else {
                        Toast.makeText(context, "VPN Permission required to tunnel traffic", Toast.LENGTH_SHORT).show()
                    }
                }

                // Notification Permission Launcher (Android 13+)
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    // Notification permission handled
                }

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                // Hardware back handler returns to Home tab if not currently on Home
                BackHandler(enabled = currentTab != AppTab.HOME) {
                    viewModel.setTab(AppTab.HOME)
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = CyberDarkBg,
                    contentWindowInsets = WindowInsets.safeDrawing,
                    topBar = {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .background(
                                                when (vpnState.status) {
                                                    VpnStatus.CONNECTED -> NeonEmerald.copy(alpha = 0.2f)
                                                    VpnStatus.CONNECTING, VpnStatus.TUNNELING -> NeonAmber.copy(alpha = 0.2f)
                                                    else -> CyanNeon.copy(alpha = 0.15f)
                                                },
                                                RoundedCornerShape(10.dp)
                                            )
                                            .border(
                                                1.dp,
                                                when (vpnState.status) {
                                                    VpnStatus.CONNECTED -> NeonEmerald
                                                    VpnStatus.CONNECTING, VpnStatus.TUNNELING -> NeonAmber
                                                    else -> CyanNeon
                                                },
                                                RoundedCornerShape(10.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Shield,
                                            contentDescription = "Logo",
                                            tint = when (vpnState.status) {
                                                VpnStatus.CONNECTED -> NeonEmerald
                                                VpnStatus.CONNECTING, VpnStatus.TUNNELING -> NeonAmber
                                                else -> CyanNeon
                                            },
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    Column {
                                        Text(
                                            text = "DpiTunnel",
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = TextPrimary
                                        )
                                        Text(
                                            text = "DPI Obfuscation Suite",
                                            fontSize = 10.sp,
                                            color = CyanNeon
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Status Badge
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                when (vpnState.status) {
                                                    VpnStatus.CONNECTED -> NeonEmerald.copy(alpha = 0.2f)
                                                    VpnStatus.CONNECTING, VpnStatus.TUNNELING -> NeonAmber.copy(alpha = 0.2f)
                                                    else -> Color(0xFF1E293B)
                                                },
                                                CircleShape
                                            )
                                            .border(
                                                1.dp,
                                                when (vpnState.status) {
                                                    VpnStatus.CONNECTED -> NeonEmerald
                                                    VpnStatus.CONNECTING, VpnStatus.TUNNELING -> NeonAmber
                                                    else -> CyberCardBorder
                                                },
                                                CircleShape
                                            )
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .background(
                                                        when (vpnState.status) {
                                                            VpnStatus.CONNECTED -> NeonEmerald
                                                            VpnStatus.CONNECTING, VpnStatus.TUNNELING -> NeonAmber
                                                            else -> TextMuted
                                                        },
                                                        CircleShape
                                                    )
                                            )
                                            Text(
                                                text = when (vpnState.status) {
                                                    VpnStatus.CONNECTED -> "SECURE"
                                                    VpnStatus.CONNECTING, VpnStatus.TUNNELING -> "LINKING"
                                                    else -> "OFFLINE"
                                                },
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = when (vpnState.status) {
                                                    VpnStatus.CONNECTED -> NeonEmerald
                                                    VpnStatus.CONNECTING, VpnStatus.TUNNELING -> NeonAmber
                                                    else -> TextMuted
                                                }
                                            )
                                        }
                                    }

                                    // Quick Ping action
                                    IconButton(
                                        onClick = { viewModel.pingSelectedProfile() },
                                        modifier = Modifier
                                            .size(36.dp)
                                            .background(CyberCardBg, CircleShape)
                                            .border(1.dp, CyberCardBorder, CircleShape)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Speed,
                                            contentDescription = "Ping",
                                            tint = CyanNeon,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                            Divider(color = CyberCardBorder)
                        }
                    },
                    bottomBar = {
                        NavigationBar(
                            containerColor = CyberCardBg,
                            contentColor = TextPrimary,
                            tonalElevation = 8.dp
                        ) {
                            AppTab.entries.forEach { tab ->
                                val selected = currentTab == tab
                                val icon = when (tab) {
                                    AppTab.HOME -> Icons.Default.VpnLock
                                    AppTab.CONFIGS -> Icons.Default.Dns
                                    AppTab.TWEAKS -> Icons.Default.Tune
                                    AppTab.LOGS -> Icons.Default.Terminal
                                    AppTab.SETTINGS -> Icons.Default.Settings
                                }

                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { viewModel.setTab(tab) },
                                    icon = {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = tab.label
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = tab.label,
                                            fontSize = 10.sp,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Color(0xFF00363D),
                                        selectedTextColor = CyanNeon,
                                        indicatorColor = CyanNeon,
                                        unselectedIconColor = TextMuted,
                                        unselectedTextColor = TextMuted
                                    )
                                )
                            }
                        }
                    }
                ) { paddingValues ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                    ) {
                        when (currentTab) {
                            AppTab.HOME -> HomeScreen(
                                viewModel = viewModel,
                                onToggleVpn = {
                                    viewModel.toggleVpn(context) {
                                        val vpnIntent = VpnService.prepare(context)
                                        if (vpnIntent != null) {
                                            vpnPrepareLauncher.launch(vpnIntent)
                                        }
                                    }
                                }
                            )
                            AppTab.CONFIGS -> ConfigsScreen(viewModel = viewModel)
                            AppTab.TWEAKS -> DpiTweaksScreen(viewModel = viewModel)
                            AppTab.LOGS -> LogsScreen(viewModel = viewModel)
                            AppTab.SETTINGS -> SettingsScreen(viewModel = viewModel)
                        }
                    }
                }
            }
        }
    }
}
