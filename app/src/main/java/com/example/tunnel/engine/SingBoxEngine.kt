package com.example.tunnel.engine

import com.example.model.LogLevel
import com.example.model.TunnelProtocol
import com.example.service.CoreWorkerIpc
import com.example.service.DpiVpnService
import com.example.service.SingBoxCoreService
import com.example.tunnel.*
import com.example.tunnel.config.SingBoxConfigBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.ServerSocket

/**
 * Phase 2 VPN Engine adapter powered by sing-box (`net.clever-vpn:libbox-android:2.1.5`),
 * executed inside the dedicated `:singbox` Android process (`SingBoxCoreService`).
 */
class SingBoxEngine(
    private val workerLauncher: CoreWorkerLauncher = CoreWorkerLauncher { context ->
        val conn = CoreWorkerIpc.bindWorker(context, SingBoxCoreService::class.java)
        BinderCoreWorkerSession(conn)
    }
) : TunnelEngine {

    override val engineName: String = "sing-box"

    override val supportedProtocols: List<TunnelProtocol> = listOf(
        TunnelProtocol.VLESS,
        TunnelProtocol.VMESS,
        TunnelProtocol.TROJAN,
        TunnelProtocol.SHADOWSOCKS
    )

    override val supportedTransports: List<String> = listOf("tcp", "ws", "grpc")

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    override val trafficMeter: TrafficMeter = TrafficMeter()
    override val trafficMonitor: TrafficMonitor = TrafficMonitor()

    @Volatile private var session: CoreWorkerSession? = null
    @Volatile private var activeConfig: EngineConfig? = null
    @Volatile private var lastTxTotal: Long = 0L
    @Volatile private var lastRxTotal: Long = 0L
    @Volatile private var onCoreDiedListener: ((String) -> Unit)? = null

    override fun setOnCoreDiedListener(listener: ((String) -> Unit)?) {
        onCoreDiedListener = listener
    }

    override suspend fun start(config: EngineConfig, protector: SocketProtector): Result<Unit> =
        withContext(Dispatchers.IO) {
            stop()
            trafficMeter.reset()
            trafficMonitor.reset()
            lastTxTotal = 0L
            lastRxTotal = 0L

            try {
                _state.value = EngineState.Validating("Validating sing-box profile configuration")
                val parsed = SingBoxConfigBuilder.validateProfile(config.profile).getOrThrow()

                val verifyPort = if (config.verificationSocksPort in 1..65535) {
                    config.verificationSocksPort
                } else {
                    allocateLoopbackPort()
                }

                val configJson = SingBoxConfigBuilder.buildConfig(
                    profile = config.profile,
                    tunFd = config.tunFd.fd,
                    mtu = config.mtu,
                    dnsServers = config.dnsServers,
                    enableIpv6 = config.enableIpv6,
                    verificationSocksPort = verifyPort
                )

                DpiVpnService.addLog(
                    LogLevel.INFO,
                    "SING_BOX",
                    "Validated ${config.profile.protocol.displayName} config (transport=${parsed.transport.id}, security=${parsed.security.id}, bytes=${configJson.length})"
                )

                val ctx = config.appContext
                    ?: throw IllegalStateException("Android Context required to launch isolated :singbox core worker")

                _state.value = EngineState.Connecting("Binding isolated :singbox worker process")
                val worker = workerLauncher.launch(ctx)
                session = worker
                activeConfig = config
                worker.setOnProcessDiedListener {
                    if (session === worker) {
                        onCoreDiedListener?.invoke("sing-box :singbox worker process died unexpectedly")
                    }
                }

                _state.value = EngineState.Handshaking("Starting libbox CommandServer on TUN")
                val workDir = config.workingDirPath ?: ctx.filesDir.absolutePath
                val startRes = worker.start(
                    configJson = configJson,
                    tunFd = config.tunFd,
                    workingDir = workDir,
                    verificationSocksPort = verifyPort,
                    protector = protector
                )

                if (!startRes.success) {
                    val err = IllegalStateException(startRes.message.ifBlank { "sing-box startup failed" })
                    _state.value = EngineState.Error(err, err.message!!)
                    stop()
                    return@withContext Result.failure(err)
                }

                DpiVpnService.addLog(
                    LogLevel.INFO,
                    "SING_BOX",
                    "Core running (${startRes.coreVersion}): ${startRes.message}"
                )

                _state.value = EngineState.Connected(config.profile.serverHost, 0L)
                Result.success(Unit)
            } catch (t: Throwable) {
                val msg = t.message ?: "Failed to launch sing-box engine"
                DpiVpnService.addLog(LogLevel.ERROR, "SING_BOX", msg)
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
            return@withContext HealthCheckResult(false, -1L, "sing-box session is not active")
        }

        val stats = currentSession.stats()
        if (!stats.isRunning) {
            return@withContext HealthCheckResult(
                false,
                -1L,
                stats.error ?: "sing-box native core is not running"
            )
        }

        val txDelta = (stats.txBytes - lastTxTotal).coerceAtLeast(0L)
        val rxDelta = (stats.rxBytes - lastRxTotal).coerceAtLeast(0L)
        if (txDelta > 0) trafficMonitor.recordUpload(txDelta)
        if (rxDelta > 0) trafficMonitor.recordDownload(rxDelta)
        lastTxTotal = stats.txBytes
        lastRxTotal = stats.rxBytes

        val verify = currentSession.verify()
        HealthCheckResult(verify.healthy, verify.latencyMs, verify.details)
    }

    private fun allocateLoopbackPort(): Int {
        return try {
            ServerSocket(0).use { it.localPort }
        } catch (_: Throwable) {
            10809
        }
    }
}
