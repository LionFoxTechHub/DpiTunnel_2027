package com.example.tunnel

import android.content.Context
import android.os.ParcelFileDescriptor
import com.example.model.*
import com.example.service.DpiVpnService
import com.example.tunnel.adapters.EngineRegistry
import com.example.tunnel.config.SingBoxConfigBuilder
import com.example.tunnel.config.XrayConfigBuilder
import com.example.tunnel.engine.SingBoxEngine
import com.example.tunnel.engine.XrayEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Central orchestrator managing tunnel engines, explicit 9-state lifecycle transitions,
 * mutually exclusive TUN ownership, tunneled connectivity verification, and crash recovery.
 */
class TunnelOrchestrator(
    private val socketProtector: SocketProtector,
    private val onStateChanged: (VpnState) -> Unit,
    private val onNotificationUpdateNeeded: (status: String, profile: TunnelProfile) -> Unit,
    private val appContext: Context? = null,
    private val workingDirPath: String? = null,
    private val engineResolver: (TunnelProfile) -> Result<TunnelEngine> = { EngineRegistry.resolveEngine(it) },
    private val tickIntervalMs: Long = 1000L
) {
    private val orchestratorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val stateMutex = Mutex()

    private var activeEngine: TunnelEngine? = null
    private var activeTunPfd: ParcelFileDescriptor? = null
    private var activeProfile: TunnelProfile? = null
    private var telemetryJob: Job? = null
    private var isRunning = false
    private var activeMtu = 1400
    private var activeIpv6 = false
    private var activeDns = listOf("1.1.1.1", "8.8.8.8")
    private var activeTunEstablisher: (suspend () -> ParcelFileDescriptor?)? = null

    private val _orchestratorState = MutableStateFlow(VpnState())
    val orchestratorState: StateFlow<VpnState> = _orchestratorState.asStateFlow()

    /**
     * Backward-compatible overload when TUN FD has already been established.
     */
    suspend fun startTunnel(
        profile: TunnelProfile,
        tunFd: ParcelFileDescriptor,
        mtu: Int,
        enableIpv6: Boolean,
        dnsServers: List<String>
    ): Result<Unit> {
        var handedOff = false
        val result = startTunnel(
            profile = profile,
            mtu = mtu,
            enableIpv6 = enableIpv6,
            dnsServers = dnsServers,
            tunEstablisher = {
                handedOff = true
                tunFd
            }
        )
        if (result.isFailure && !handedOff) {
            try { tunFd.close() } catch (_: Throwable) {}
        }
        return result
    }

    /**
     * Runs the complete validated VPN lifecycle:
     * CONFIG_VALIDATION -> CORE_INITIALIZING -> TUN_ESTABLISHING -> CORE_RUNNING -> CONNECTIVITY_VERIFYING -> CONNECTED.
     */
    suspend fun startTunnel(
        profile: TunnelProfile,
        mtu: Int,
        enableIpv6: Boolean,
        dnsServers: List<String>,
        tunEstablisher: suspend () -> ParcelFileDescriptor?
    ): Result<Unit> = stateMutex.withLock {
        stopTunnelInternal("Starting profile: ${profile.name}")

        activeProfile = profile
        activeMtu = mtu
        activeIpv6 = enableIpv6
        activeDns = dnsServers
        activeTunEstablisher = tunEstablisher

        // 1. CONFIG_VALIDATION (before creating any TUN interface)
        updateState {
            copy(
                status = VpnStatus.CONFIG_VALIDATION,
                activeProfile = profile,
                activeEngineName = profile.preferredEngine.displayName,
                connectedDurationSeconds = 0L,
                downloadSpeedBps = 0L,
                uploadSpeedBps = 0L,
                totalDownloadBytes = 0L,
                totalUploadBytes = 0L,
                errorMessage = null,
                activeObfuscationSummary = "${profile.dpiTweak.tcpFragmentMode.label} [SNI: ${profile.dpiTweak.sniHost}]"
            )
        }

        val validationErr = validateProfileConfig(profile)
        if (validationErr != null) {
            return failStartup(validationErr)
        }

        val engineResult = engineResolver(profile)
        if (engineResult.isFailure) {
            return failStartup(
                engineResult.exceptionOrNull()
                    ?: UnsupportedOperationException("No compatible engine for ${profile.protocol.displayName}")
            )
        }
        val engine = engineResult.getOrThrow()
        activeEngine = engine
        engine.setOnCoreDiedListener { reason ->
            orchestratorScope.launch {
                attemptCrashRecovery(engine, profile, reason)
            }
        }

        // 2. CORE_INITIALIZING
        updateState {
            copy(
                status = VpnStatus.CORE_INITIALIZING,
                activeEngineName = engine.engineName
            )
        }
        DpiVpnService.addLog(
            LogLevel.INFO,
            "ORCHESTRATOR",
            "Initializing ${engine.engineName} for ${profile.protocol.displayName} (${profile.name})"
        )

        try {
            // 3. TUN_ESTABLISHING
            updateState { copy(status = VpnStatus.TUN_ESTABLISHING) }
            val tunPfd = try {
                tunEstablisher()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                return failStartup(IllegalStateException("TUN establish failed: ${t.message}", t))
            }
            if (tunPfd == null) {
                return failStartup(IllegalStateException("Failed to establish Android TUN interface (permission missing or revoked)"))
            }
            activeTunPfd = tunPfd

            val config = EngineConfig(
                profile = profile,
                tunFd = tunPfd,
                mtu = mtu,
                enableIpv6 = enableIpv6,
                dnsServers = dnsServers,
                appContext = appContext,
                workingDirPath = workingDirPath
            )

            // Launch core engine
            val startResult = engine.start(config, socketProtector)
            if (startResult.isFailure) {
                val ex = startResult.exceptionOrNull()
                    ?: IllegalStateException("Engine ${engine.engineName} failed to start")
                return failStartup(ex)
            }

            // 4. CORE_RUNNING
            updateState { copy(status = VpnStatus.CORE_RUNNING) }

            // 5. CONNECTIVITY_VERIFYING (must pass tunneled verification before reporting CONNECTED)
            updateState { copy(status = VpnStatus.CONNECTIVITY_VERIFYING) }
            val health = engine.checkHealth()
            if (!health.isHealthy) {
                return failStartup(
                    IllegalStateException("Tunnel connectivity verification failed: ${health.details}")
                )
            }

            DpiVpnService.addLog(
                LogLevel.INFO,
                "HEALTH",
                "${engine.engineName} verified active (${health.latencyMs}ms): ${health.details}"
            )

            // 6. CONNECTED
            isRunning = true
            updateState {
                copy(
                    status = VpnStatus.CONNECTED,
                    currentVirtualIp = "10.8.0.2",
                    remoteServerIp = profile.serverHost,
                    errorMessage = null
                )
            }
            onNotificationUpdateNeeded("Connected (${engine.engineName})", profile)
            startTelemetryLoop(engine, profile)

            Result.success(Unit)
        } catch (ce: CancellationException) {
            isRunning = false
            activeEngine?.setOnCoreDiedListener(null)
            try { activeEngine?.stop() } catch (_: Throwable) {}
            activeEngine = null
            closeActiveTun()
            updateState { copy(status = VpnStatus.DISCONNECTED, downloadSpeedBps = 0L, uploadSpeedBps = 0L) }
            throw ce
        }
    }

    private fun validateProfileConfig(profile: TunnelProfile): Throwable? {
        if (profile.serverHost.isBlank() || profile.serverPort !in 1..65535) {
            return IllegalArgumentException("Invalid endpoint ${profile.serverHost}:${profile.serverPort}")
        }
        if (profile.protocol == TunnelProtocol.HTTP) {
            val tweakCheck = profile.dpiTweak.validate()
            if (!tweakCheck.isValid) {
                return IllegalArgumentException(tweakCheck.issues.joinToString("; "))
            }
        }
        val isPhase2Protocol = profile.protocol in setOf(
            TunnelProtocol.VLESS,
            TunnelProtocol.VMESS,
            TunnelProtocol.TROJAN,
            TunnelProtocol.SHADOWSOCKS,
            TunnelProtocol.V2RAY
        )
        if (isPhase2Protocol) {
            val check = when (profile.preferredEngine) {
                CoreEngineType.SING_BOX -> SingBoxConfigBuilder.validateProfile(profile)
                CoreEngineType.XRAY_CORE -> XrayConfigBuilder.validateProfile(profile)
                CoreEngineType.AUTO -> XrayConfigBuilder.validateProfile(profile)
            }
            if (check.isFailure) {
                return check.exceptionOrNull()
            }
        }
        return null
    }

    private suspend fun failStartup(error: Throwable): Result<Unit> {
        val msg = error.message ?: error::class.java.simpleName
        DpiVpnService.addLog(LogLevel.ERROR, "ORCHESTRATOR", msg)
        isRunning = false
        activeEngine?.setOnCoreDiedListener(null)
        try { activeEngine?.stop() } catch (_: Throwable) {}
        activeEngine = null
        closeActiveTun()
        updateState {
            copy(
                status = VpnStatus.ERROR,
                errorMessage = msg,
                downloadSpeedBps = 0L,
                uploadSpeedBps = 0L
            )
        }
        return Result.failure(error)
    }

    private fun startTelemetryLoop(engine: TunnelEngine, profile: TunnelProfile) {
        telemetryJob?.cancel()
        telemetryJob = orchestratorScope.launch {
            var seconds = 0L
            var consecutiveHealthFailures = 0

            while (isActive && isRunning) {
                delay(tickIntervalMs)
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

                if (seconds % 3 == 0L) {
                    onNotificationUpdateNeeded(
                        "Connected - ↓ ${VpnState.formatSpeed(downRate)} ↑ ${VpnState.formatSpeed(upRate)}",
                        profile
                    )
                }

                // Periodic health check and crash recovery
                if (seconds % 15 == 0L) {
                    val health = engine.checkHealth()
                    if (!health.isHealthy) {
                        consecutiveHealthFailures++
                        DpiVpnService.addLog(
                            LogLevel.WARN,
                            "HEALTH",
                            "Health check failed ($consecutiveHealthFailures): ${health.details}"
                        )
                        if (consecutiveHealthFailures >= 2) {
                            attemptCrashRecovery(engine, profile, health.details)
                            break
                        }
                    } else {
                        consecutiveHealthFailures = 0
                        DpiVpnService.addLog(
                            LogLevel.INFO,
                            "HEARTBEAT",
                            "Verified (${engine.engineName}). Tx: ${VpnState.formatBytes(totalUp)} | Rx: ${VpnState.formatBytes(totalDown)} (${health.latencyMs}ms)"
                        )
                    }
                }
            }
        }
    }

    private suspend fun attemptCrashRecovery(
        failedEngine: TunnelEngine,
        profile: TunnelProfile,
        reason: String
    ) {
        stateMutex.withLock {
            if (!isRunning || activeEngine !== failedEngine) return@withLock
            DpiVpnService.addLog(
                LogLevel.WARN,
                "RECOVERY",
                "Core crashed or degraded ($reason); attempting fail-closed recovery..."
            )
            updateState { copy(status = VpnStatus.CORE_INITIALIZING) }

            try { failedEngine.stop() } catch (_: Throwable) {}
            val currentTun = activeTunPfd
            if (currentTun == null) {
                isRunning = false
                activeEngine = null
                updateState { copy(status = VpnStatus.ERROR, errorMessage = "TUN lost during crash recovery") }
                return@withLock
            }

            val restartCfg = EngineConfig(
                profile = profile,
                tunFd = currentTun,
                mtu = activeMtu,
                enableIpv6 = activeIpv6,
                dnsServers = activeDns,
                appContext = appContext,
                workingDirPath = workingDirPath
            )
            val restartRes = failedEngine.start(restartCfg, socketProtector)
            if (restartRes.isSuccess) {
                updateState { copy(status = VpnStatus.CONNECTIVITY_VERIFYING) }
                val verify = failedEngine.checkHealth()
                if (verify.isHealthy) {
                    DpiVpnService.addLog(LogLevel.INFO, "RECOVERY", "Core recovered cleanly")
                    updateState { copy(status = VpnStatus.CONNECTED, errorMessage = null) }
                    startTelemetryLoop(failedEngine, profile)
                    return@withLock
                }
            }

            DpiVpnService.addLog(LogLevel.ERROR, "RECOVERY", "Crash recovery failed; closing tunnel")
            isRunning = false
            try { failedEngine.stop() } catch (_: Throwable) {}
            activeEngine = null
            closeActiveTun()
            updateState {
                copy(
                    status = VpnStatus.ERROR,
                    errorMessage = "Core crashed and recovery failed: $reason",
                    downloadSpeedBps = 0L,
                    uploadSpeedBps = 0L
                )
            }
        }
    }

    suspend fun stopTunnel(reason: String) = stateMutex.withLock {
        stopTunnelInternal(reason)
    }

    private suspend fun stopTunnelInternal(reason: String) {
        if (!isRunning && activeEngine == null && activeTunPfd == null) {
            if (_orchestratorState.value.status != VpnStatus.DISCONNECTED &&
                _orchestratorState.value.status != VpnStatus.ERROR
            ) {
                updateState { copy(status = VpnStatus.DISCONNECTED) }
            }
            return
        }

        DpiVpnService.addLog(LogLevel.WARN, "ORCHESTRATOR", "Stopping tunnel: $reason")
        updateState { copy(status = VpnStatus.DISCONNECTING) }
        isRunning = false
        telemetryJob?.cancel()
        telemetryJob = null

        activeEngine?.setOnCoreDiedListener(null)
        try {
            activeEngine?.stop()
        } catch (_: Throwable) {}
        activeEngine = null
        closeActiveTun()

        updateState {
            copy(
                status = VpnStatus.DISCONNECTED,
                downloadSpeedBps = 0L,
                uploadSpeedBps = 0L
            )
        }
    }

    private fun closeActiveTun() {
        try {
            activeTunPfd?.close()
        } catch (_: Throwable) {}
        activeTunPfd = null
    }

    fun handleNetworkLost() {
        orchestratorScope.launch {
            DpiVpnService.addLog(LogLevel.WARN, "NETWORK", "Physical network connection lost")
            stateMutex.withLock {
                if (isRunning) {
                    updateState { copy(status = VpnStatus.CONNECTIVITY_VERIFYING) }
                }
            }
        }
    }

    fun handleNetworkAvailable() {
        orchestratorScope.launch {
            DpiVpnService.addLog(LogLevel.INFO, "NETWORK", "Physical network re-established")
            stateMutex.withLock {
                val engine = activeEngine
                if (isRunning && engine != null) {
                    val verify = engine.checkHealth()
                    if (verify.isHealthy) {
                        updateState { copy(status = VpnStatus.CONNECTED, errorMessage = null) }
                    }
                }
            }
        }
    }

    private fun updateState(transform: VpnState.() -> VpnState) {
        val newState = _orchestratorState.value.transform()
        _orchestratorState.value = newState
        onStateChanged(newState)
    }

    fun release() {
        orchestratorScope.cancel()
        closeActiveTun()
    }
}
