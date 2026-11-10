package com.example.tunnel

import android.os.ParcelFileDescriptor
import com.example.model.*
import com.example.service.DpiVpnService
import com.example.tunnel.adapters.EngineRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Central orchestrator managing tunnel engines, verification, lifecycle,
 * and genuine real-time traffic statistics.
 */
class TunnelOrchestrator(
    private val socketProtector: SocketProtector,
    private val onStateChanged: (VpnState) -> Unit,
    private val onNotificationUpdateNeeded: (status: String, profile: TunnelProfile) -> Unit
) {
    private val orchestratorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val stateMutex = Mutex()

    private var activeEngine: TunnelEngine? = null
    private var telemetryJob: Job? = null
    private var isRunning = false

    private val _orchestratorState = MutableStateFlow(VpnState())
    val orchestratorState: StateFlow<VpnState> = _orchestratorState.asStateFlow()

    suspend fun startTunnel(
        profile: TunnelProfile,
        tunFd: ParcelFileDescriptor,
        mtu: Int,
        enableIpv6: Boolean,
        dnsServers: List<String>
    ): Result<Unit> = stateMutex.withLock {
        stopTunnelInternal("Starting new profile: ${profile.name}")

        isRunning = true
        updateState {
            copy(
                status = VpnStatus.CONNECTING,
                activeProfile = profile,
                connectedDurationSeconds = 0L,
                downloadSpeedBps = 0L,
                uploadSpeedBps = 0L,
                totalDownloadBytes = 0L,
                totalUploadBytes = 0L,
                activeObfuscationSummary = "${profile.dpiTweak.tcpFragmentMode.label} [SNI: ${profile.dpiTweak.sniHost}]"
            )
        }

        DpiVpnService.addLog(LogLevel.INFO, "ORCHESTRATOR", "Locating engine adapter for protocol ${profile.protocol.displayName}...")
        val engine = EngineRegistry.getEngineForProtocol(profile.protocol)
        if (engine == null) {
            val msg = "Protocol ${profile.protocol.displayName} requires Phase 2 core integration. Supported in Phase 1: HTTP, SSH, SOCKS, SlowDNS, OpenVPN, Hysteria 1/2."
            DpiVpnService.addLog(LogLevel.ERROR, "ENGINE", msg)
            updateState { copy(status = VpnStatus.DISCONNECTED) }
            isRunning = false
            return Result.failure(UnsupportedOperationException(msg))
        }
        activeEngine = engine

        val config = EngineConfig(
            profile = profile,
            tunFd = tunFd,
            mtu = mtu,
            enableIpv6 = enableIpv6,
            dnsServers = dnsServers
        )

        updateState { copy(status = VpnStatus.TUNNELING) }

        DpiVpnService.addLog(LogLevel.INFO, "ENGINE", "Launching ${engine::class.simpleName}...")
        val startResult = engine.start(config, socketProtector)

        if (startResult.isFailure) {
            val ex = startResult.exceptionOrNull()
            val errorMsg = ex?.localizedMessage ?: "Engine startup failed"
            DpiVpnService.addLog(LogLevel.ERROR, "ENGINE", "Tunnel failed to establish: $errorMsg")

            updateState {
                copy(
                    status = VpnStatus.DISCONNECTED,
                    downloadSpeedBps = 0L,
                    uploadSpeedBps = 0L
                )
            }
            isRunning = false
            return Result.failure(ex ?: IllegalStateException(errorMsg))
        }

        // Verify remote tunnel connection before declaring CONNECTED
        val health = engine.checkHealth()
        if (!health.isHealthy) {
            val err = IllegalStateException("Remote health check failed: ${health.details}")
            DpiVpnService.addLog(LogLevel.ERROR, "HEALTH", err.message!!)
            engine.stop()
            updateState { copy(status = VpnStatus.DISCONNECTED) }
            isRunning = false
            return Result.failure(err)
        }

        DpiVpnService.addLog(LogLevel.INFO, "HEALTH", "Tunnel verified active (Latency: ${health.latencyMs}ms)")

        updateState {
            copy(
                status = VpnStatus.CONNECTED,
                currentVirtualIp = "10.8.0.2",
                remoteServerIp = profile.serverHost
            )
        }

        // Start genuine traffic meter & telemetry loop
        startTelemetryLoop(engine, profile)

        Result.success(Unit)
    }

    private fun startTelemetryLoop(engine: TunnelEngine, profile: TunnelProfile) {
        telemetryJob?.cancel()
        telemetryJob = orchestratorScope.launch {
            var seconds = 0L

            while (isActive && isRunning) {
                delay(1000)
                seconds++

                val (upRate, downRate) = engine.trafficMonitor.tick()
                val totalUp = engine.trafficMonitor.totalUploadedBytes
                val totalDown = engine.trafficMonitor.totalDownloadedBytes

                updateState {
                    copy(
                        connectedDurationSeconds = seconds,
                        downloadSpeedBps = downRate,
                        uploadSpeedBps = upRate,
                        totalDownloadBytes = totalDown,
                        totalUploadBytes = totalUp
                    )
                }

                // Update notification periodically
                if (seconds % 3 == 0L) {
                    onNotificationUpdateNeeded(
                        "Connected - ↓ ${VpnState.formatSpeed(downRate)} ↑ ${VpnState.formatSpeed(upRate)}",
                        profile
                    )
                }

                // Periodic health check
                if (seconds % 30 == 0L) {
                    val health = engine.checkHealth()
                    if (!health.isHealthy) {
                        DpiVpnService.addLog(LogLevel.WARN, "HEALTH", "Degraded link: ${health.details}")
                    } else {
                        DpiVpnService.addLog(
                            LogLevel.INFO,
                            "HEARTBEAT",
                            "Tunnel verified. Tx: ${VpnState.formatBytes(totalUp)} | Rx: ${VpnState.formatBytes(totalDown)} (Ping: ${health.latencyMs}ms)"
                        )
                    }
                }
            }
        }
    }

    suspend fun stopTunnel(reason: String) = stateMutex.withLock {
        stopTunnelInternal(reason)
    }

    private suspend fun stopTunnelInternal(reason: String) {
        if (!isRunning && activeEngine == null) return

        DpiVpnService.addLog(LogLevel.WARN, "ORCHESTRATOR", "Stopping tunnel: $reason")
        isRunning = false
        telemetryJob?.cancel()
        telemetryJob = null

        activeEngine?.stop()
        activeEngine = null

        updateState {
            copy(
                status = VpnStatus.DISCONNECTED,
                downloadSpeedBps = 0L,
                uploadSpeedBps = 0L
            )
        }
    }

    fun handleNetworkLost() {
        orchestratorScope.launch {
            DpiVpnService.addLog(LogLevel.WARN, "NETWORK", "Physical network connection lost")
            stateMutex.withLock {
                if (isRunning) {
                    updateState { copy(status = VpnStatus.CONNECTING) }
                }
            }
        }
    }

    fun handleNetworkAvailable() {
        orchestratorScope.launch {
            DpiVpnService.addLog(LogLevel.INFO, "NETWORK", "Physical network re-established")
        }
    }

    private fun updateState(transform: VpnState.() -> VpnState) {
        val newState = _orchestratorState.value.transform()
        _orchestratorState.value = newState
        onStateChanged(newState)
    }

    fun release() {
        orchestratorScope.cancel()
    }
}
