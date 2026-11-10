package com.example.tunnel

import com.example.model.TunnelProtocol
import kotlinx.coroutines.flow.StateFlow

sealed class EngineState {
    object Idle : EngineState()
    data class Connecting(val stage: String) : EngineState()
    data class Handshaking(val stage: String) : EngineState()
    data class Connected(val remoteAddress: String, val latencyMs: Long) : EngineState()
    data class Error(val cause: Throwable, val message: String) : EngineState()
    object Disconnected : EngineState()
}

/**
 * Common contract for all VPN tunnel engines (Direct Socket / HTTP Injector,
 * Xray-core, sing-box, SSH, and WireGuard).
 */
interface TunnelEngine {
    val supportedProtocols: List<TunnelProtocol>
    val state: StateFlow<EngineState>
    val trafficMeter: TrafficMeter
    val trafficMonitor: TrafficMonitor

    /**
     * Initializes the engine, validates configuration, starts remote socket connections
     * with outbound protection, and begins bidirectional forwarding with the TUN interface.
     */
    suspend fun start(config: EngineConfig, protector: SocketProtector): Result<Unit>

    /**
     * Stops the engine, cancels background jobs, flushes sockets, and releases resources.
     */
    suspend fun stop()

    /**
     * Performs an active end-to-end health check of the remote tunnel.
     */
    suspend fun checkHealth(): HealthCheckResult
}
