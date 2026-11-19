package com.example.tunnel

import com.example.model.CoreEngineType
import com.example.model.TunnelProtocol
import kotlinx.coroutines.flow.StateFlow

sealed class EngineState {
    object Idle : EngineState()
    data class Validating(val stage: String) : EngineState()
    data class Connecting(val stage: String) : EngineState()
    data class Handshaking(val stage: String) : EngineState()
    data class Verifying(val stage: String) : EngineState()
    data class Connected(val remoteAddress: String, val latencyMs: Long) : EngineState()
    data class Error(val cause: Throwable, val message: String) : EngineState()
    object Disconnected : EngineState()
}

data class EngineCapability(
    val engineId: String,
    val engineName: String,
    val coreType: CoreEngineType?,
    val supportedProtocols: List<TunnelProtocol>,
    val supportedTransports: List<String>,
    val supportedSecurityModes: List<String>,
    val unsupportedFeatures: List<String>
)

/**
 * Common contract for all VPN tunnel engines (Phase 1 Direct/HTTP/SSH/SOCKS/DNS/OpenVPN/Hysteria
 * and Phase 2 Xray-core / sing-box).
 */
interface TunnelEngine {
    val engineName: String
        get() = this::class.simpleName ?: "UnknownEngine"
    val supportedProtocols: List<TunnelProtocol>
    val supportedTransports: List<String>
        get() = listOf("tcp")
    val state: StateFlow<EngineState>
    val trafficMeter: TrafficMeter
    val trafficMonitor: TrafficMonitor

    /**
     * Validates configuration, starts the engine with outbound socket protection,
     * and binds to the Android TUN interface.
     */
    suspend fun start(config: EngineConfig, protector: SocketProtector): Result<Unit>

    /**
     * Stops the engine, cancels background jobs, flushes sockets, and releases resources.
     */
    suspend fun stop()

    /**
     * Performs an active connectivity/runtime verification through the active engine.
     * Implementations MUST NOT perform unprotected direct TCP socket checks to claim tunnel health.
     */
    suspend fun checkHealth(): HealthCheckResult

    /**
     * Registers a callback invoked immediately if an out-of-process native worker dies unexpectedly.
     */
    fun setOnCoreDiedListener(listener: ((String) -> Unit)?) {}
}
