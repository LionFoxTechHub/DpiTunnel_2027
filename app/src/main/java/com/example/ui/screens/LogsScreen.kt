package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.LogLevel
import com.example.ui.MainViewModel
import com.example.ui.theme.*

@Composable
fun LogsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val vpnState by viewModel.vpnState.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val listState = rememberLazyListState()

    var selectedLevel by remember { mutableStateOf<LogLevel?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var autoScroll by remember { mutableStateOf(true) }

    val filteredLogs = remember(vpnState.logs, selectedLevel, searchQuery) {
        vpnState.logs.filter { entry ->
            (selectedLevel == null || entry.level == selectedLevel) &&
            (searchQuery.isEmpty() ||
             entry.message.contains(searchQuery, ignoreCase = true) ||
             entry.tag.contains(searchQuery, ignoreCase = true))
        }
    }

    LaunchedEffect(filteredLogs.size, autoScroll) {
        if (autoScroll && filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Top Toolbar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Tunnel & DPI Console",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = CyanNeon
                )
                Text(
                    text = "${filteredLogs.size} events captured",
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(
                    onClick = {
                        val allLogText = vpnState.logs.joinToString("\n") {
                            "[${it.formattedTime}] [${it.level.name}] [${it.tag}] ${it.message}"
                        }
                        clipboardManager.setText(AnnotatedString(allLogText))
                        Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .background(CyberCardBg, RoundedCornerShape(10.dp))
                        .border(1.dp, CyberCardBorder, RoundedCornerShape(10.dp))
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Logs", tint = CyanNeon, modifier = Modifier.size(18.dp))
                }

                IconButton(
                    onClick = { viewModel.clearLogs() },
                    modifier = Modifier
                        .size(40.dp)
                        .background(CyberCardBg, RoundedCornerShape(10.dp))
                        .border(1.dp, CyberCardBorder, RoundedCornerShape(10.dp))
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Logs", tint = NeonRed, modifier = Modifier.size(18.dp))
                }
            }
        }

        // Filter chips & search
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = selectedLevel == null,
                onClick = { selectedLevel = null },
                label = { Text("ALL", fontSize = 10.sp) }
            )
            FilterChip(
                selected = selectedLevel == LogLevel.OBFS,
                onClick = { selectedLevel = if (selectedLevel == LogLevel.OBFS) null else LogLevel.OBFS },
                label = { Text("DPI OBFS", fontSize = 10.sp) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = NeonAmber.copy(alpha = 0.2f), selectedLabelColor = NeonAmber)
            )
            FilterChip(
                selected = selectedLevel == LogLevel.INFO,
                onClick = { selectedLevel = if (selectedLevel == LogLevel.INFO) null else LogLevel.INFO },
                label = { Text("INFO", fontSize = 10.sp) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CyanNeon.copy(alpha = 0.2f), selectedLabelColor = CyanNeon)
            )
            FilterChip(
                selected = selectedLevel == LogLevel.WARN,
                onClick = { selectedLevel = if (selectedLevel == LogLevel.WARN) null else LogLevel.WARN },
                label = { Text("WARN", fontSize = 10.sp) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = NeonAmber.copy(alpha = 0.2f), selectedLabelColor = NeonAmber)
            )
            FilterChip(
                selected = selectedLevel == LogLevel.ERROR,
                onClick = { selectedLevel = if (selectedLevel == LogLevel.ERROR) null else LogLevel.ERROR },
                label = { Text("ERROR", fontSize = 10.sp) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = NeonRed.copy(alpha = 0.2f), selectedLabelColor = NeonRed)
            )
        }

        // Terminal Log Box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF05080E), RoundedCornerShape(14.dp))
                .border(1.dp, CyberCardBorder, RoundedCornerShape(14.dp))
                .padding(12.dp)
        ) {
            if (filteredLogs.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Terminal, contentDescription = null, tint = TextMuted, modifier = Modifier.size(36.dp))
                    Text(
                        text = "No tunnel events logged yet",
                        fontSize = 12.sp,
                        color = TextMuted
                    )
                    Text(
                        text = "Start the VPN or run a DPI Probe to see handshake telemetry",
                        fontSize = 10.sp,
                        color = TextSecondary
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredLogs) { entry ->
                        val levelColor = when (entry.level) {
                            LogLevel.OBFS -> NeonAmber
                            LogLevel.INFO -> CyanNeon
                            LogLevel.WARN -> Color(0xFFFF9E00)
                            LogLevel.ERROR -> NeonRed
                        }

                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = entry.formattedTime,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextMuted
                                )
                                Text(
                                    text = "[${entry.tag}]",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = levelColor
                                )
                            }
                            Text(
                                text = entry.message,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFE2E8F0),
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
