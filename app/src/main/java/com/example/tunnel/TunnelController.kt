package com.example.tunnel

import android.content.Context
import com.example.model.LogLevel
import com.example.model.LogEntry
import com.example.model.TunnelProfile
import com.example.model.VpnState
import com.example.service.DpiVpnService
import kotlinx.coroutines.flow.StateFlow

/**
 * TunnelController provides the primary high-level API for the application UI and services
 * to initiate, monitor, and terminate VPN tunnels.
 */
object TunnelController {

    val vpnState: StateFlow<VpnState>
        get() = DpiVpnService.vpnState

    fun startTunnel(context: Context, profile: TunnelProfile) {
        DpiVpnService.start(context, profile)
    }

    fun stopTunnel(context: Context) {
        DpiVpnService.stop(context)
    }

    fun addLog(level: LogLevel, tag: String, message: String) {
        DpiVpnService.addLog(level, tag, message)
    }

    fun clearLogs() {
        DpiVpnService.clearLogs()
    }
}
