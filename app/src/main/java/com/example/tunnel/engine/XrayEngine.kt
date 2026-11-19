package com.example.tunnel.engine

import com.example.model.LogLevel
import com.example.model.TunnelProtocol
import com.example.service.CoreWorkerIpc
import com.example.service.DpiVpnService
import com.example.service.XrayCoreService
import com.example.tunnel.*
import com.example.tunnel.config.XrayConfigBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.ServerSocket

/**
 * Phase 2 VPN Engine adapter powered by Xray-core (`io.github.toolshubofficial:libxray:26.6.27`),
 * executed inside the dedicated `:xray` Android process (`XrayCoreService`).
 */
class XrayEngine(
    private val workerLauncher: CoreWorkerLauncher = CoreWorkerLauncher { context ->
        val conn = CoreWorkerIpc.bindWorker(context, XrayCoreService::class.java)
        BinderCoreWorkerSession(conn)
    }
) : TunnelEngine {

    override val engineName: String = "Xray-core"

    override val supportedProtocols: List<TunnelProtocol> = listOf(
        TunnelProtocol.VLESS,
        TunnelProtocol.VMESS,
        TunnelProtocol.TROJAN,
        TunnelProtocol.SHADOWSOCKS,
        TunnelProtocol.V2RAY
    )

    override val supportedTransports: List<String> = listOf("tcp", "ws", "grpc", "xhttp")

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    override val trafficMeter: TrafficMeter = TrafficMeter()
    override val trafficMonitor: TrafficMonitor = TrafficMonitor()

    @Volatile private var session: CoreWorkerSession? = null
    @Volatile private var activeConfig: EngineConfig? = null
    @Volatile private var onCoreDiedListener: ((String) -> Unit)? = null

    override fun setOnCoreDiedListener(listener: ((String) -> Unit)?) {
        onCoreDiedListener = listener
    }

    override suspend fun start(config: EngineConfig, protector: SocketProtector): Result<Unit> =
        withContext(Dispatchers.IO) {
            stop()
            trafficMeter.reset()
            trafficMonitor.reset()

            try {
                _state.value = EngineState.Validating("Validating Xray-core profile configuration")
                val parsed = XrayConfigBuilder.validateProfile(config.profile).getOrThrow()

                val verifyPort = if (config.verificationSocksPort in 1..65535) {
                    config.verificationSocksPort
                } else {
                    allocateLoopbackPort()
                }

                val configJson = XrayConfigBuilder.buildConfig(
                    profile = config.profile,
                    localSocksPort = verifyPort,
                    dnsServers = config.dnsServers,
                    useTunInbound = true,
                    mtu = config.mtu
                )

                DpiVpnService.addLog(
                    LogLevel.INFO,
                    "XRAY",
                    "Validated ${config.profile.protocol.displayName} config (transport=${parsed.transport.id}, security=${parsed.security.id}, bytes=${configJson.length})"
                )

                val ctx = config.appContext
                    ?: throw IllegalStateException("Android Context required to launch isolated :xray core worker")

                _state.value = EngineState.Connecting("Binding isolated :xray worker process")
                val worker = workerLauncher.launch(ctx)
                session = worker
                activeConfig = config
                worker.setOnProcessDiedListener {
                    if (session === worker) {
                        onCoreDiedListener?.invoke("Xray-core :xray worker process died unexpectedly")
                    }
                }

                _state.value = EngineState.Handshaking("Starting Xray-core with native TUN + DialerController")
                val workDir = config.workingDirPath ?: ctx.filesDir.absolutePath
                val startRes = worker.start(
                    configJson = configJson,
                    tunFd = config.tunFd,
                    workingDir = workDir,
                    verificationSocksPort = verifyPort,
                    protector = protector
                )

                if (!startRes.success) {
                    val err = IllegalStateException(startRes.message.ifBlank { "Xray-core startup failed" })
                    _state.value = EngineState.Error(err, err.message!!)
                    stop()
                    return@withContext Result.failure(err)
                }

                DpiVpnService.addLog(
                    LogLevel.INFO,
                    "XRAY",
                    "Core running (${startRes.coreVersion}): ${startRes.message}"
                )

                _state.value = EngineState.Connected(config.profile.serverHost, 0L)
                Result.success(Unit)
            } catch (t: Throwable) {
                val msg = t.message ?: "Failed to launch Xray engine"
                DpiVpnService.addLog(LogLevel.ERROR, "XRAY", msg)
                _state.value = EngineState.Error(t, msg)
                stopInternal(markDisconnected = false)
                Result.failure(t)
            }
        }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        stopInternal(markDisconnected = true)
    }

    private fun stopInternal(markDisconnected: Boolean) {
        val current = session
        session = null
        activeConfig = null
        try {
            current?.stop()
        } catch (_: Throwable) {}
        if (markDisconnected) {
            _state.value = EngineState.Disconnected
        }
    }

    override suspend fun checkHealth(): HealthCheckResult = withContext(Dispatchers.IO) {
        val currentSession = session
        if (currentSession == null || activeConfig == null) {
            return@withContext HealthCheckResult(false, -1L, "Xray session is not active")
        }

        val stats = currentSession.stats()
        if (!stats.isRunning) {
            return@withContext HealthCheckResult(
                false,
                -1L,
                stats.error ?: "Xray-core native runtime is not running"
            )
        }

        val verify = currentSession.verify()
        HealthCheckResult(verify.healthy, verify.latencyMs, verify.details)
    }

    private fun allocateLoopbackPort(): Int {
        return try {
            ServerSocket(0).use { it.localPort }
        } catch (_: Throwable) {
            10808
        }
    }
}
