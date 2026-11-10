package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.MainViewModel
import com.example.ui.theme.*

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val killSwitch by viewModel.killSwitchEnabled.collectAsState()
    val mtu by viewModel.mtuSize.collectAsState()
    val autoReconnect by viewModel.autoReconnect.collectAsState()
    val dnsFallback by viewModel.dnsFallback.collectAsState()
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var tempMtu by remember(mtu) { mutableFloatStateOf(mtu.toFloat()) }
    var tempDns by remember(dnsFallback) { mutableStateOf(dnsFallback) }
    var ipv6Enabled by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Tunnel & Security Settings",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = CyanNeon
        )

        // Security card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CyberCardBg),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(CyberCardBorder))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Security & Network Defense",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                // Kill Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("VPN Kill Switch", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        Text("Drop all network traffic if the encrypted tunnel drops unexpectedly", fontSize = 11.sp, color = TextMuted)
                    }
                    Switch(
                        checked = killSwitch,
                        onCheckedChange = {
                            viewModel.killSwitchEnabled.value = it
                            Toast.makeText(context, if (it) "Kill switch activated" else "Kill switch disabled", Toast.LENGTH_SHORT).show()
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = CyanNeon, checkedTrackColor = CyanNeon.copy(alpha = 0.4f))
                    )
                }

                HorizontalDivider(color = CyberCardBorder)

                // Auto reconnect
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-Reconnect", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        Text("Automatically re-establish connection on Wi-Fi/Mobile network handoffs", fontSize = 11.sp, color = TextMuted)
                    }
                    Switch(
                        checked = autoReconnect,
                        onCheckedChange = { viewModel.autoReconnect.value = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = CyanNeon, checkedTrackColor = CyanNeon.copy(alpha = 0.4f))
                    )
                }

                HorizontalDivider(color = CyberCardBorder)

                // IPv6
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Route IPv6 Traffic", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        Text("Tunnel IPv6 packets or block them to prevent ISP IPv6 leaks", fontSize = 11.sp, color = TextMuted)
                    }
                    Switch(
                        checked = ipv6Enabled,
                        onCheckedChange = { ipv6Enabled = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = CyanNeon, checkedTrackColor = CyanNeon.copy(alpha = 0.4f))
                    )
                }
            }
        }

        // Protocol & Tunnel Hardware card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CyberCardBg),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(CyberCardBorder))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "Virtual Interface Parameters",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                // MTU Slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Tunnel MTU Size", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        Text("${tempMtu.toInt()} bytes", fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = CyanNeon)
                    }
                    Text("Lower MTU (1360-1400) prevents packet drops when DPI packet fragmentation is active.", fontSize = 11.sp, color = TextMuted)
                    Slider(
                        value = tempMtu,
                        onValueChange = {
                            tempMtu = it
                            viewModel.mtuSize.value = it.toInt()
                        },
                        valueRange = 1280f..1500f,
                        steps = 21,
                        colors = SliderDefaults.colors(thumbColor = CyanNeon, activeTrackColor = CyanNeon)
                    )
                }

                HorizontalDivider(color = CyberCardBorder)

                // Fallback DNS
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Fallback Upstream DNS", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Text("Used if Encrypted DoH server is unreachable:", fontSize = 11.sp, color = TextMuted)
                    OutlinedTextField(
                        value = tempDns,
                        onValueChange = {
                            tempDns = it
                            viewModel.dnsFallback.value = it
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanNeon,
                            unfocusedBorderColor = CyberCardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        singleLine = true
                    )
                }
            }
        }

        // About LionFox Tech & DpiTunnel
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CyberCardElevated),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(CyberCardBorder))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(CyanNeon.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = CyanNeon, modifier = Modifier.size(22.dp))
                    }
                    Column {
                        Text("DpiTunnel", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text("com.lionfoxtech.DpiTunnel", fontSize = 11.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
                    }
                }

                HorizontalDivider(color = CyberCardBorder)

                Text(
                    text = "Engineering by LionFox Tech. Built with high-performance traffic obfuscation mechanisms specifically designed to bypass aggressive Deep Packet Inspection firewalls, stateful SNI filters, and QoS bandwidth throttling.",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    lineHeight = 16.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Engine Version", fontSize = 11.sp, color = TextMuted)
                    Text("v2.8-multi-obfs", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = NeonEmerald)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Supported Protocols", fontSize = 11.sp, color = TextMuted)
                    Text("13 Tunnel Engines", fontSize = 11.sp, color = CyanNeon)
                }
            }
        }
    }
}
