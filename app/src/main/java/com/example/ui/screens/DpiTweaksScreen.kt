package com.example.ui.screens

import android.widget.Toast
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
import com.example.engine.DpiObfuscationEngine
import com.example.model.*
import com.example.ui.MainViewModel
import com.example.ui.theme.*

@Composable
fun DpiTweaksScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val selectedProfile by viewModel.selectedProfile.collectAsState()
    val isProbing by viewModel.isProbing.collectAsState()
    val probeProgress by viewModel.probeProgressText.collectAsState()
    val probeResult by viewModel.probeResult.collectAsState()
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var sniHost by remember(selectedProfile) { mutableStateOf(selectedProfile.dpiTweak.sniHost) }
    var tcpMode by remember(selectedProfile) { mutableStateOf(selectedProfile.dpiTweak.tcpFragmentMode) }
    var fragmentDelay by remember(selectedProfile) { mutableFloatStateOf(selectedProfile.dpiTweak.fragmentDelayMs.toFloat()) }
    var fakeHttp by remember(selectedProfile) { mutableStateOf(selectedProfile.dpiTweak.fakeHttpRequest) }
    var payloadText by remember(selectedProfile) { mutableStateOf(selectedProfile.dpiTweak.httpPayload) }
    var dohProvider by remember(selectedProfile) { mutableStateOf(selectedProfile.dpiTweak.dohProvider) }
    var badChecksum by remember(selectedProfile) { mutableStateOf(selectedProfile.dpiTweak.badChecksumEvasion) }
    var quicPadding by remember(selectedProfile) { mutableStateOf(selectedProfile.dpiTweak.quicJunkPadding) }

    fun applyChanges() {
        val updated = selectedProfile.dpiTweak.copy(
            sniHost = sniHost,
            tcpFragmentMode = tcpMode,
            fragmentDelayMs = fragmentDelay.toLong(),
            fakeHttpRequest = fakeHttp,
            httpPayload = payloadText,
            dohProvider = dohProvider,
            badChecksumEvasion = badChecksum,
            quicJunkPadding = quicPadding
        )
        viewModel.updateDpiTweak(updated)
        Toast.makeText(context, "DPI Tweaks Applied to ${selectedProfile.name}", Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "DPI Obfuscation Engine",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = CyanNeon
                )
                Text(
                    text = "Custom bypass parameters for ${selectedProfile.name}",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }

            Button(
                onClick = { applyChanges() },
                colors = ButtonDefaults.buttonColors(containerColor = CyanNeon, contentColor = Color(0xFF00363D)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Apply", fontWeight = FontWeight.Bold)
            }
        }

        // Section 1: DPI Diagnostic Probe Tool
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CyberCardBg),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(CyberCardBorder))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Troubleshoot, contentDescription = null, tint = NeonAmber)
                        Text(
                            text = "DPI PENETRATION PROBE",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeonAmber
                        )
                    }

                    Button(
                        onClick = { viewModel.runDpiProbe() },
                        enabled = !isProbing,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonAmber, contentColor = Color(0xFF452B00)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(if (isProbing) "Testing..." else "Run Probe")
                    }
                }

                Text(
                    text = "Diagnoses whether local middleboxes or ISP deep packet inspection algorithms are filtering SNI handshakes or blocking packets.",
                    fontSize = 11.sp,
                    color = TextSecondary
                )

                if (isProbing) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = NeonAmber,
                        trackColor = CyberCardElevated
                    )
                    Text(text = probeProgress, fontSize = 11.sp, color = NeonAmber, fontFamily = FontFamily.Monospace)
                }

                probeResult?.let { res ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (res.isDohSuccessful) Color(0xFF0D2818) else Color(0xFF330C12)
                        ),
                        border = CardDefaults.outlinedCardBorder().copy(
                            brush = androidx.compose.ui.graphics.SolidColor(
                                if (res.isDohSuccessful) NeonEmerald else NeonRed
                            )
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (res.isDohSuccessful) "PROBE PASSED" else "INSPECTION DETECTED",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (res.isDohSuccessful) NeonEmerald else NeonRed
                                )
                                Text(
                                    text = "Latency: ${res.handshakeLatencyMs}ms",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextPrimary
                                )
                            }
                            Text(
                                text = "Resolved Host: ${res.targetHost} -> ${res.dohResolvedIp ?: "Blocked"}",
                                fontSize = 11.sp,
                                color = TextSecondary,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = res.diagnosticSummary,
                                fontSize = 11.sp,
                                color = TextPrimary
                            )
                            Text(
                                text = "Tip: ${res.recommendedTweak}",
                                fontSize = 11.sp,
                                color = CyanNeon,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        // Section 2: SNI Camouflage (Bug Host)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CyberCardBg),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(CyberCardBorder))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "SNI Camouflage (Fronting Host)",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "Replaces the Server Name Indication in the TLS handshake with a trusted domain to bypass firewall white-lists.",
                    fontSize = 11.sp,
                    color = TextSecondary
                )

                OutlinedTextField(
                    value = sniHost,
                    onValueChange = { sniHost = it },
                    label = { Text("SNI Bug Host") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanNeon,
                        unfocusedBorderColor = CyberCardBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    singleLine = true
                )

                // Quick presets
                Text(text = "Popular Zero-Rated / Fronting Hosts:", fontSize = 11.sp, color = TextMuted)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val presets = listOf("cloudflare.com", "zoom.us", "speedtest.net", "freefiremobile.com", "fast.com", "microsoft.com")
                    presets.forEach { preset ->
                        FilterChip(
                            selected = sniHost == preset,
                            onClick = { sniHost = preset },
                            label = { Text(preset, fontSize = 10.sp) }
                        )
                    }
                }
            }
        }

        // Section 3: TCP ClientHello Packet Fragmentation
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CyberCardBg),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(CyberCardBorder))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "TCP Handshake Fragmentation",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "Breaks the initial TLS ClientHello across multiple TCP segments so stateful firewalls cannot reconstruct the target SNI domain.",
                    fontSize = 11.sp,
                    color = TextSecondary
                )

                TcpFragmentMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (tcpMode == mode) CyberCardElevated else Color.Transparent)
                            .clickable { tcpMode = mode }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = tcpMode == mode, onClick = { tcpMode = mode })
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(mode.label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text(mode.description, fontSize = 10.sp, color = TextMuted)
                        }
                    }
                }

                if (tcpMode == TcpFragmentMode.DELAYED_SPLIT) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Inter-packet Delay", fontSize = 11.sp, color = TextSecondary)
                            Text("${fragmentDelay.toInt()} ms", fontSize = 11.sp, color = CyanNeon, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = fragmentDelay,
                            onValueChange = { fragmentDelay = it },
                            valueRange = 5f..50f,
                            steps = 8,
                            colors = SliderDefaults.colors(thumbColor = CyanNeon, activeTrackColor = CyanNeon)
                        )
                    }
                }

                // Visualizer of fragmentation
                val plan = remember(sniHost, tcpMode, fragmentDelay) {
                    DpiObfuscationEngine.calculateFragmentationPlan(sniHost, tcpMode, fragmentDelay.toLong())
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF090D14))
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Packet Transmission Plan:", fontSize = 10.sp, color = TextMuted)
                        Text(
                            text = plan.explanation,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = NeonEmerald
                        )
                    }
                }
            }
        }

        // Section 4: HTTP Request / Payload Generator
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CyberCardBg),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(CyberCardBorder))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "HTTP Payload Injector",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Switch(
                        checked = fakeHttp,
                        onCheckedChange = { fakeHttp = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = CyanNeon, checkedTrackColor = CyanNeon.copy(alpha = 0.4f))
                    )
                }

                if (fakeHttp) {
                    Text(
                        text = "Inject custom HTTP headers before tunnel socket negotiation. Tokens will be automatically evaluated:",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )

                    // Template Presets
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = false,
                            onClick = { payloadText = DpiTweakSettings.DEFAULT_PAYLOAD },
                            label = { Text("Standard Tweak", fontSize = 10.sp) }
                        )
                        FilterChip(
                            selected = false,
                            onClick = { payloadText = DpiTweakSettings.WEBSOCKET_PAYLOAD },
                            label = { Text("WebSocket Upgrade", fontSize = 10.sp) }
                        )
                        FilterChip(
                            selected = false,
                            onClick = { payloadText = DpiTweakSettings.DIRECT_FRONTING_PAYLOAD },
                            label = { Text("Direct Fronting", fontSize = 10.sp) }
                        )
                    }

                    // Token Insert Buttons
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf("[host]", "[port]", "[host_port]", "[crlf]", "[ua]", "[split]").forEach { token ->
                            AssistChip(
                                onClick = { payloadText += token },
                                label = { Text(token, fontSize = 9.sp, fontFamily = FontFamily.Monospace) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = payloadText,
                        onValueChange = { payloadText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp),
                        shape = RoundedCornerShape(10.dp),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanNeon,
                            unfocusedBorderColor = CyberCardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )

                    // Live expanded preview
                    val expandedPreview = remember(payloadText, selectedProfile) {
                        DpiObfuscationEngine.expandPayload(
                            payloadText,
                            selectedProfile.serverHost,
                            selectedProfile.serverPort,
                            "Mozilla/5.0 (Mobile; DPI-Bypass)"
                        )
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF090D14))
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("Expanded Output Preview:", fontSize = 9.sp, color = TextMuted)
                            Text(
                                text = expandedPreview,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFE2E8F0)
                            )
                        }
                    }
                }
            }
        }

        // Section 5: Encrypted DNS (DoH) Provider
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CyberCardBg),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(CyberCardBorder))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "DNS Over HTTPS (Anti-Poisoning)",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "Prevents ISP DNS hijacking, redirection, and poisoning by querying resolver over an encrypted TLS connection.",
                    fontSize = 11.sp,
                    color = TextSecondary
                )

                DohProvider.entries.forEach { provider ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (dohProvider == provider) CyberCardElevated else Color.Transparent)
                            .clickable { dohProvider = provider }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = dohProvider == provider, onClick = { dohProvider = provider })
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(provider.displayName, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text(provider.endpoint, fontSize = 10.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }

        // Section 6: Additional Anti-DPI Decoy Tactics
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CyberCardBg),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(CyberCardBorder))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Advanced Anti-Inspection Tactics",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Bad Checksum Decoy Packet", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        Text("Sends decoy packet with deliberately invalid checksum to desync DPI state machines", fontSize = 10.sp, color = TextMuted)
                    }
                    Switch(
                        checked = badChecksum,
                        onCheckedChange = { badChecksum = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = CyanNeon, checkedTrackColor = CyanNeon.copy(alpha = 0.4f))
                    )
                }

                HorizontalDivider(color = CyberCardBorder)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("QUIC / UDP Junk Padding", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        Text("Appends random byte padding to prevent packet length fingerprinting on Hysteria & WireGuard", fontSize = 10.sp, color = TextMuted)
                    }
                    Switch(
                        checked = quicPadding,
                        onCheckedChange = { quicPadding = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = CyanNeon, checkedTrackColor = CyanNeon.copy(alpha = 0.4f))
                    )
                }
            }
        }
    }
}
