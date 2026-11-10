package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.TunnelRepository
import com.example.model.*
import com.example.ui.MainViewModel
import com.example.ui.components.ProtocolBadge
import com.example.ui.theme.*

@Composable
fun ConfigsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val profiles by viewModel.profiles.collectAsState()
    val selectedProfile by viewModel.selectedProfile.collectAsState()
    val activeFilter by viewModel.protocolFilter.collectAsState()
    val isPingingAll by viewModel.isPingingAll.collectAsState()
    val pingProgress by viewModel.pingProgress.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    var showImportDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var profileToEdit by remember { mutableStateOf<TunnelProfile?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredProfiles = remember(profiles, activeFilter, searchQuery) {
        profiles.filter { profile ->
            (activeFilter == null || profile.protocol == activeFilter) &&
            (searchQuery.isEmpty() ||
             profile.name.contains(searchQuery, ignoreCase = true) ||
             profile.serverHost.contains(searchQuery, ignoreCase = true) ||
             profile.cityName.contains(searchQuery, ignoreCase = true))
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = CyberDarkBg,
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    profileToEdit = null
                    showEditDialog = true
                },
                containerColor = CyanNeon,
                contentColor = Color(0xFF00363D),
                modifier = Modifier.testTag("add_config_fab")
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Add Config")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Search & Action Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search configs, hosts, locations...", fontSize = 12.sp, color = TextMuted) },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = "Search", tint = TextMuted, modifier = Modifier.size(18.dp))
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanNeon,
                        unfocusedBorderColor = CyberCardBorder,
                        focusedContainerColor = CyberCardBg,
                        unfocusedContainerColor = CyberCardBg,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    singleLine = true
                )

                IconButton(
                    onClick = { showImportDialog = true },
                    modifier = Modifier
                        .size(50.dp)
                        .background(CyberCardBg, RoundedCornerShape(12.dp))
                        .border(1.dp, CyberCardBorder, RoundedCornerShape(12.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentPaste,
                        contentDescription = "Import Config",
                        tint = CyanNeon
                    )
                }

                IconButton(
                    onClick = { viewModel.pingAllProfiles() },
                    enabled = !isPingingAll,
                    modifier = Modifier
                        .size(50.dp)
                        .background(CyberCardBg, RoundedCornerShape(12.dp))
                        .border(1.dp, CyberCardBorder, RoundedCornerShape(12.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = "Ping All",
                        tint = if (isPingingAll) NeonAmber else NeonEmerald
                    )
                }
            }

            // Ping progress text if active
            if (isPingingAll && pingProgress.isNotEmpty()) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = NeonEmerald,
                    trackColor = CyberCardElevated
                )
                Text(
                    text = pingProgress,
                    fontSize = 11.sp,
                    color = NeonEmerald,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Protocol Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = activeFilter == null,
                    onClick = { viewModel.setProtocolFilter(null) },
                    label = { Text("ALL (${profiles.size})", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyanNeon.copy(alpha = 0.2f),
                        selectedLabelColor = CyanNeon
                    )
                )

                TunnelProtocol.entries.forEach { proto ->
                    val count = profiles.count { it.protocol == proto }
                    FilterChip(
                        selected = activeFilter == proto,
                        onClick = { viewModel.setProtocolFilter(if (activeFilter == proto) null else proto) },
                        label = { Text("${proto.displayName} ($count)", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = proto.badgeColor.copy(alpha = 0.2f),
                            selectedLabelColor = proto.badgeColor
                        )
                    )
                }
            }

            // Config List
            if (filteredProfiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FilterAltOff,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(48.dp)
                        )
                        Text("No configs matching your filter", color = TextSecondary, fontSize = 14.sp)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredProfiles, key = { it.id }) { profile ->
                        val isSelected = profile.id == selectedProfile.id
                        ConfigItemCard(
                            profile = profile,
                            isSelected = isSelected,
                            onSelect = { viewModel.selectProfile(profile) },
                            onEdit = {
                                profileToEdit = profile
                                showEditDialog = true
                            },
                            onDelete = {
                                viewModel.deleteProfile(profile.id)
                                Toast.makeText(context, "Deleted ${profile.name}", Toast.LENGTH_SHORT).show()
                            },
                            onExport = {
                                val json = TunnelRepository.exportConfigToJson(profile)
                                clipboardManager.setText(AnnotatedString(json))
                                Toast.makeText(context, "Config copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }
    }

    // Import Dialog
    if (showImportDialog) {
        var importInput by remember { mutableStateOf("") }
        var importError by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import Config", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Paste a tunnel link (vless://, vmess://, ss://, trojan://, hy2://, http://) or DpiTunnel JSON:",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    OutlinedTextField(
                        value = importInput,
                        onValueChange = {
                            importInput = it
                            importError = false
                        },
                        placeholder = { Text("vless://...@...:443 or {...}", fontSize = 11.sp, color = TextMuted) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanNeon,
                            unfocusedBorderColor = CyberCardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        isError = importError
                    )
                    if (importError) {
                        Text("Invalid format. Please check link syntax.", color = NeonRed, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val success = viewModel.importConfig(importInput)
                        if (success) {
                            showImportDialog = false
                            Toast.makeText(context, "Config imported successfully!", Toast.LENGTH_SHORT).show()
                        } else {
                            importError = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanNeon, contentColor = Color(0xFF00363D))
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = CyberCardBg,
            titleContentColor = TextPrimary
        )
    }

    // Add / Edit Dialog
    if (showEditDialog) {
        AddEditConfigDialog(
            initialProfile = profileToEdit,
            onDismiss = { showEditDialog = false },
            onSave = { updated ->
                viewModel.saveProfile(updated)
                showEditDialog = false
                Toast.makeText(context, "Saved ${updated.name}", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
private fun ConfigItemCard(
    profile: TunnelProfile,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onSelect() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) CyberCardElevated else CyberCardBg
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                if (isSelected) CyanNeon else CyberCardBorder
            ),
            width = if (isSelected) 1.5.dp else 1.dp
        )
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(profile.countryFlag, fontSize = 24.sp)
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = profile.name,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) CyanNeon else TextPrimary
                            )
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .background(CyanNeon, CircleShape)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("ACTIVE", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00363D))
                                }
                            }
                        }
                        Text(
                            text = "${profile.cityName} • ${profile.serverHost}:${profile.serverPort}",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ProtocolBadge(protocol = profile.protocol)

                    Box {
                        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = TextSecondary)
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier.background(CyberCardElevated)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Edit Configuration", color = TextPrimary) },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = CyanNeon) },
                                onClick = {
                                    showMenu = false
                                    onEdit()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Export JSON / Link", color = TextPrimary) },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = ElectricBlue) },
                                onClick = {
                                    showMenu = false
                                    onExport()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", color = NeonRed) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = NeonRed) },
                                onClick = {
                                    showMenu = false
                                    onDelete()
                                }
                            )
                        }
                    }
                }
            }

            // Obfuscation Badges row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Ping chip
                val pingColor = when {
                    profile.pingMs in 1..80 -> NeonEmerald
                    profile.pingMs in 81..150 -> NeonAmber
                    profile.pingMs > 150 -> NeonRed
                    else -> TextMuted
                }
                Box(
                    modifier = Modifier
                        .background(pingColor.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (profile.pingMs > 0) "${profile.pingMs} ms" else "-- ms",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = pingColor,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Obfs Sni Badge
                Box(
                    modifier = Modifier
                        .background(Color(0xFF1E293B), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "SNI: ${profile.dpiTweak.sniHost}",
                        fontSize = 10.sp,
                        color = Color(0xFF94A3B8)
                    )
                }

                // Frag mode badge
                Box(
                    modifier = Modifier
                        .background(Color(0xFF1E293B), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = profile.dpiTweak.tcpFragmentMode.label,
                        fontSize = 10.sp,
                        color = CyanNeon
                    )
                }
            }
        }
    }
}

@Composable
private fun AddEditConfigDialog(
    initialProfile: TunnelProfile?,
    onDismiss: () -> Unit,
    onSave: (TunnelProfile) -> Unit
) {
    var name by remember { mutableStateOf(initialProfile?.name ?: "Custom DPI Node") }
    var protocol by remember { mutableStateOf(initialProfile?.protocol ?: TunnelProtocol.VLESS) }
    var host by remember { mutableStateOf(initialProfile?.serverHost ?: "127.0.0.1") }
    var port by remember { mutableStateOf((initialProfile?.serverPort ?: 443).toString()) }
    var uuid by remember { mutableStateOf(initialProfile?.uuidOrPassword ?: "") }
    var countryCode by remember { mutableStateOf(initialProfile?.countryCode ?: "US") }
    var cityName by remember { mutableStateOf(initialProfile?.cityName ?: "Custom") }
    var sniHost by remember { mutableStateOf(initialProfile?.dpiTweak?.sniHost ?: "cloudflare.com") }
    var fragMode by remember { mutableStateOf(initialProfile?.dpiTweak?.tcpFragmentMode ?: TcpFragmentMode.SPLIT_AT_SNI) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialProfile == null) "New Tunnel Config" else "Edit Config", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Profile Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Text("Protocol", fontSize = 12.sp, color = TextMuted)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TunnelProtocol.entries.forEach { p ->
                        FilterChip(
                            selected = protocol == p,
                            onClick = { protocol = p },
                            label = { Text(p.displayName, fontSize = 11.sp) }
                        )
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Server Host / IP") },
                        modifier = Modifier.weight(2f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("Port") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                OutlinedTextField(
                    value = uuid,
                    onValueChange = { uuid = it },
                    label = { Text("UUID / Password / Key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = countryCode,
                        onValueChange = { countryCode = it.take(2).uppercase() },
                        label = { Text("Country (2 chars)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = cityName,
                        onValueChange = { cityName = it },
                        label = { Text("City") },
                        modifier = Modifier.weight(1.5f),
                        singleLine = true
                    )
                }

                HorizontalDivider(color = CyberCardBorder)
                Text("DPI Obfuscation Settings", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = NeonAmber)

                OutlinedTextField(
                    value = sniHost,
                    onValueChange = { sniHost = it },
                    label = { Text("SNI Camouflage Host") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Text("TCP Fragmentation Mode", fontSize = 12.sp, color = TextMuted)
                TcpFragmentMode.entries.forEach { mode ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { fragMode = mode }
                            .padding(vertical = 4.dp)
                    ) {
                        RadioButton(selected = fragMode == mode, onClick = { fragMode = mode })
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(mode.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Text(mode.description, fontSize = 10.sp, color = TextMuted)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val portInt = port.toIntOrNull() ?: protocol.defaultPort
                    val newProfile = (initialProfile ?: TunnelProfile(
                        name = name,
                        protocol = protocol,
                        serverHost = host,
                        isCustom = true
                    )).copy(
                        name = name,
                        protocol = protocol,
                        serverHost = host,
                        serverPort = portInt,
                        uuidOrPassword = uuid,
                        countryCode = countryCode.ifEmpty { "US" },
                        cityName = cityName,
                        dpiTweak = (initialProfile?.dpiTweak ?: DpiTweakSettings()).copy(
                            sniHost = sniHost,
                            tcpFragmentMode = fragMode
                        )
                    )
                    onSave(newProfile)
                },
                colors = ButtonDefaults.buttonColors(containerColor = CyanNeon, contentColor = Color(0xFF00363D))
            ) {
                Text("Save Config")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        },
        containerColor = CyberCardBg,
        titleContentColor = TextPrimary
    )
}
