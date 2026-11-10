package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.TunnelProtocol
import com.example.model.VpnStatus
import com.example.ui.AppTab
import com.example.ui.MainViewModel
import com.example.ui.components.HeroDial
import com.example.ui.components.ProtocolBadge
import com.example.ui.components.SpeedMeter
import com.example.ui.theme.*

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onToggleVpn: () -> Unit,
    modifier: Modifier = Modifier
) {
    val vpnState by viewModel.vpnState.collectAsState()
    val selectedProfile by viewModel.selectedProfile.collectAsState()
    val scrollState = rememberScrollState()
    var showServerSelectorModal by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Header Banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CyberCardBg),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(CyberCardBorder))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Professional multi-tunneling VPN",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyanNeon
                    )
                    Box(
                        modifier = Modifier
                            .background(
                                if (vpnState.isConnected) NeonEmerald.copy(alpha = 0.2f)
                                else Color(0xFF1E293B),
                                CircleShape
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (vpnState.isConnected) "PROTECTED" else "UNPROTECTED",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (vpnState.isConnected) NeonEmerald else TextMuted
                        )
                    }
                }

                Text(
                    text = "DpiTunnel integrates advanced anti-censorship protocols with packet fragmentation and SNI spoofing to evade deep packet inspection.",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    lineHeight = 17.sp
                )

                // Feature pills matching image 1
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FeaturePill(icon = Icons.AutoMirrored.Filled.AltRoute, label = "Multi-tunneling")
                    FeaturePill(icon = Icons.Default.CloudQueue, label = "DPI Obfuscated")
                    FeaturePill(icon = Icons.Default.Code, label = "Custom Tweaks")
                }
            }
        }

        // Hero Dial Connection Button
        HeroDial(
            vpnState = vpnState,
            onToggle = onToggleVpn
        )

        // Live Upload/Download Speed
        SpeedMeter(vpnState = vpnState)

        // Active Selected Server Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showServerSelectorModal = true }
                .testTag("selected_server_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CyberCardBg),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(CyberCardBorder))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = selectedProfile.countryFlag,
                        fontSize = 28.sp
                    )
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = selectedProfile.name,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                        Text(
                            text = "${selectedProfile.serverHost}:${selectedProfile.serverPort}",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ProtocolBadge(protocol = selectedProfile.protocol)
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Switch Server",
                        tint = TextSecondary
                    )
                }
            }
        }

        // Active DPI Strategy Overview Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.setTab(AppTab.TWEAKS) },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CyberCardElevated),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(CyberCardBorder))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = "DPI Tweaks",
                            tint = NeonAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "ACTIVE DPI EVASION STRATEGY",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeonAmber,
                            letterSpacing = 0.5.sp
                        )
                    }
                    Text(
                        text = "Customize >",
                        fontSize = 11.sp,
                        color = CyanNeon,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(text = "SNI Camouflage", fontSize = 10.sp, color = TextMuted)
                        Text(
                            text = selectedProfile.dpiTweak.sniHost,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                    }
                    Column {
                        Text(text = "TCP Fragmentation", fontSize = 10.sp, color = TextMuted)
                        Text(
                            text = selectedProfile.dpiTweak.tcpFragmentMode.label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = NeonEmerald
                        )
                    }
                    Column {
                        Text(text = "DoH Engine", fontSize = 10.sp, color = TextMuted)
                        Text(
                            text = selectedProfile.dpiTweak.dohProvider.displayName.substringBefore(" "),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ElectricBlue
                        )
                    }
                }
            }
        }

        // Supported Tunnels Section matching screenshot 2
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SUPPORTED TUNNELS",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "${TunnelProtocol.entries.size} Protocols",
                    fontSize = 11.sp,
                    color = CyanNeon
                )
            }

            // Horizontal scroll of all 13 protocols with badges
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TunnelProtocol.entries.forEach { protocol ->
                    ProtocolBadge(
                        protocol = protocol,
                        isSelected = selectedProfile.protocol == protocol,
                        onClick = {
                            viewModel.setProtocolFilter(protocol)
                            viewModel.setTab(AppTab.CONFIGS)
                        }
                    )
                }
            }
        }
    }

    // Server Selection Modal Sheet
    if (showServerSelectorModal) {
        val allProfiles by viewModel.profiles.collectAsState()
        AlertDialog(
            onDismissRequest = { showServerSelectorModal = false },
            title = {
                Text("Select Server Profile", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    allProfiles.forEach { profile ->
                        val isCurrent = profile.id == selectedProfile.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isCurrent) CyberCardElevated else Color.Transparent)
                                .clickable {
                                    viewModel.selectProfile(profile)
                                    showServerSelectorModal = false
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(profile.countryFlag, fontSize = 20.sp)
                                Column {
                                    Text(
                                        profile.name,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isCurrent) CyanNeon else TextPrimary
                                    )
                                    Text(
                                        "${profile.protocol.displayName} • ${profile.cityName}",
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                }
                            }
                            ProtocolBadge(protocol = profile.protocol)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showServerSelectorModal = false }) {
                    Text("Close", color = CyanNeon)
                }
            },
            containerColor = CyberCardBg,
            textContentColor = TextPrimary,
            titleContentColor = TextPrimary
        )
    }
}

@Composable
private fun FeaturePill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(
        modifier = Modifier
            .background(Color(0xFF162332), RoundedCornerShape(20.dp))
            .border(1.dp, Color(0xFF22364C), RoundedCornerShape(20.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = CyanNeon,
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFFCBD5E1)
        )
    }
}
