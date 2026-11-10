package com.example.ui

import android.content.Context
import android.net.VpnService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.TunnelRepository
import com.example.engine.DpiObfuscationEngine
import com.example.engine.DpiProbeResult
import com.example.model.*
import com.example.service.DpiVpnService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class AppTab(val label: String) {
    HOME("Tunnel"),
    CONFIGS("Configs"),
    TWEAKS("DPI Tweaks"),
    LOGS("Logs"),
    SETTINGS("Settings")
}

class MainViewModel : ViewModel() {

    val vpnState: StateFlow<VpnState> = DpiVpnService.vpnState
    val profiles: StateFlow<List<TunnelProfile>> = TunnelRepository.profiles
    val selectedProfile: StateFlow<TunnelProfile> = TunnelRepository.selectedProfile

    private val _currentTab = MutableStateFlow(AppTab.HOME)
    val currentTab: StateFlow<AppTab> = _currentTab.asStateFlow()

    private val _protocolFilter = MutableStateFlow<TunnelProtocol?>(null)
    val protocolFilter: StateFlow<TunnelProtocol?> = _protocolFilter.asStateFlow()

    // DPI Probe State
    private val _isProbing = MutableStateFlow(false)
    val isProbing: StateFlow<Boolean> = _isProbing.asStateFlow()

    private val _probeProgressText = MutableStateFlow("")
    val probeProgressText: StateFlow<String> = _probeProgressText.asStateFlow()

    private val _probeResult = MutableStateFlow<DpiProbeResult?>(null)
    val probeResult: StateFlow<DpiProbeResult?> = _probeResult.asStateFlow()

    // Ping All State
    private val _isPingingAll = MutableStateFlow(false)
    val isPingingAll: StateFlow<Boolean> = _isPingingAll.asStateFlow()

    private val _pingProgress = MutableStateFlow("")
    val pingProgress: StateFlow<String> = _pingProgress.asStateFlow()

    // Dialogs State
    var showImportDialog = MutableStateFlow(false)
    var showAddConfigDialog = MutableStateFlow(false)
    var editingProfile = MutableStateFlow<TunnelProfile?>(null)

    // User Preferences
    val killSwitchEnabled = MutableStateFlow(false)
    val mtuSize = MutableStateFlow(1400)
    val autoReconnect = MutableStateFlow(true)
    val dnsFallback = MutableStateFlow("1.1.1.1, 8.8.8.8")

    fun setTab(tab: AppTab) {
        _currentTab.value = tab
    }

    fun setProtocolFilter(protocol: TunnelProtocol?) {
        _protocolFilter.value = protocol
    }

    fun selectProfile(profile: TunnelProfile) {
        TunnelRepository.selectProfile(profile)
    }

    fun toggleVpn(context: Context, prepareVpnLauncher: () -> Unit) {
        val currentState = vpnState.value
        if (currentState.isConnected || currentState.isTransitioning) {
            DpiVpnService.stop(context)
        } else {
            val vpnIntent = VpnService.prepare(context)
            if (vpnIntent != null) {
                // Requires system prompt for VPN authorization
                prepareVpnLauncher()
            } else {
                startVpn(context)
            }
        }
    }

    fun startVpn(context: Context) {
        val profile = selectedProfile.value
        DpiVpnService.start(context, profile)
    }

    fun pingSelectedProfile() {
        viewModelScope.launch {
            val profile = selectedProfile.value
            TunnelRepository.pingProfile(profile)
        }
    }

    fun pingAllProfiles() {
        if (_isPingingAll.value) return
        viewModelScope.launch {
            _isPingingAll.value = true
            TunnelRepository.pingAllProfiles { current, total ->
                _pingProgress.value = "Pinging servers: $current / $total"
            }
            _isPingingAll.value = false
            _pingProgress.value = ""
        }
    }

    fun runDpiProbe(targetHost: String? = null) {
        if (_isProbing.value) return
        viewModelScope.launch {
            _isProbing.value = true
            _probeResult.value = null
            val profile = selectedProfile.value
            val host = targetHost ?: profile.serverHost
            val result = DpiObfuscationEngine.runDpiProbe(
                targetHost = host,
                targetPort = profile.serverPort,
                tweak = profile.dpiTweak
            ) { progress ->
                _probeProgressText.value = progress
            }
            _probeResult.value = result
            _isProbing.value = false
        }
    }

    fun importConfig(text: String): Boolean {
        val imported = TunnelRepository.importConfigFromString(text) ?: return false
        TunnelRepository.addCustomProfile(imported)
        return true
    }

    fun saveProfile(profile: TunnelProfile) {
        if (profiles.value.any { it.id == profile.id }) {
            TunnelRepository.updateProfile(profile)
        } else {
            TunnelRepository.addCustomProfile(profile)
        }
    }

    fun deleteProfile(id: String) {
        TunnelRepository.deleteProfile(id)
    }

    fun updateDpiTweak(newTweak: DpiTweakSettings) {
        val current = selectedProfile.value
        val updated = current.copy(dpiTweak = newTweak)
        TunnelRepository.updateProfile(updated)
    }

    fun clearLogs() {
        DpiVpnService.clearLogs()
    }
}
