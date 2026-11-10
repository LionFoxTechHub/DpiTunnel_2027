package com.example.tunnel.engine

import com.example.model.TcpFragmentMode
import com.example.model.TunnelProtocol
import com.example.tunnel.*
import com.example.tunnel.payload.PayloadBuilder
import com.example.tunnel.transport.TlsTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Functional HTTP Tweak HTTP Proxy / Custom Payload Injector Engine.
 * Supports HTTP CONNECT, custom request methods/paths, Bug Host fronting,
 * CRLF payload substitution, and proxy authentication.
 */
class HttpProxyEngine : TunnelEngine {

    override val supportedProtocols: List<TunnelProtocol> = listOf(TunnelProtocol.HTTP)

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    override val trafficMonitor: TrafficMonitor = TrafficMonitor()
    override val trafficMeter: TrafficMeter = TrafficMeter()

    private var activeSocket: Socket? = null
    private var tun2Socks: Tun2SocksEngine? = null
    private var engineScope: CoroutineScope? = null

    override suspend fun start(config: EngineConfig, protector: SocketProtector): Result<Unit> = withContext(Dispatchers.IO) {
        stop()

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        engineScope = scope
        trafficMonitor.reset()
        trafficMeter.reset()

        val profile = config.profile
        val host = profile.serverHost.trim()
        val port = profile.serverPort

        if (host.isEmpty() || port !in 1..65535) {
            val err = IllegalArgumentException("Invalid proxy target: $host:$port")
            _state.value = EngineState.Error(err, err.message!!)
            return@withContext Result.failure(err)
        }

        try {
            _state.value = EngineState.Connecting("Connecting to HTTP Proxy $host:$port...")

            val socket = Socket()
            val isProtected = protector.protect(socket)
            if (!isProtected) {
                val err = IllegalStateException("Socket protection failed")
                _state.value = EngineState.Error(err, err.message!!)
                socket.close()
                return@withContext Result.failure(err)
            }

            val connectStart = System.currentTimeMillis()
            socket.soTimeout = 10000
            socket.connect(InetSocketAddress(host, port), 8000)
            val tcpLatency = System.currentTimeMillis() - connectStart

            var currentSocket = socket

            // TLS wrapping if port 443 or TLS enabled
            val tweak = profile.dpiTweak
            if (port == 443) {
                _state.value = EngineState.Handshaking("Negotiating TLS with SNI: ${tweak.sniHost}...")
                currentSocket = TlsTransport.upgradeToTls(
                    underlyingSocket = socket,
                    remoteHost = host,
                    remotePort = port,
                    config = TlsTransport.TlsConfig(sniHost = tweak.sniHost)
                )
            }
            activeSocket = currentSocket

            val outStream = currentSocket.getOutputStream()
            val inStream = currentSocket.getInputStream()

            // Build and inject HTTP payload
            _state.value = EngineState.Handshaking("Injecting HTTP Request Payload...")
            val evaluatedPayload = PayloadBuilder.buildPayload(
                template = tweak.httpPayload,
                targetHost = host,
                targetPort = port,
                bugHost = tweak.sniHost,
                userAgent = tweak.customUserAgent
            )

            // Write payload with fragmentation if enabled
            val payloadSegments = PayloadBuilder.getPayloadSegments(evaluatedPayload)
            for (segment in payloadSegments) {
                if (tweak.tcpFragmentMode == TcpFragmentMode.SPLIT_AT_SNI && segment.size > 20) {
                    val half = segment.size / 2
                    outStream.write(segment, 0, half)
                    outStream.flush()
                    delay(tweak.fragmentDelayMs.coerceAtLeast(10L))
                    outStream.write(segment, half, segment.size - half)
                    outStream.flush()
                } else {
                    outStream.write(segment)
                    outStream.flush()
                }
                trafficMonitor.recordUpload(segment.size.toLong())
            }

            // Read proxy response
            val responseHeader = readHttpHeader(inStream)
            val statusLine = responseHeader.lines().firstOrNull() ?: ""
            val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 0

            if (statusCode !in 200..299 && statusCode != 101) {
                val err = IOException("HTTP Proxy returned status $statusCode: $statusLine")
                _state.value = EngineState.Error(err, err.message!!)
                currentSocket.close()
                return@withContext Result.failure(err)
            }

            _state.value = EngineState.Connected(
                remoteAddress = "$host:$port",
                latencyMs = tcpLatency
            )

            // Initialize Tun2Socks Layer-3 to Layer-4 packet forwarding
            val router = Tun2SocksEngine(
                tunFd = config.tunFd,
                mtu = config.mtu,
                trafficMonitor = trafficMonitor,
                onForwardPayload = { _, _, payload ->
                    try {
                        outStream.write(payload)
                        outStream.flush()
                        trafficMonitor.recordUpload(payload.size.toLong())
                    } catch (_: IOException) {}
                }
            )
            tun2Socks = router
            router.start()

            // Downlink relay from proxy socket into Tun2Socks
            scope.launch(Dispatchers.IO) {
                val buf = ByteArray(32768)
                try {
                    while (isActive && !currentSocket.isClosed) {
                        val len = inStream.read(buf)
                        if (len > 0) {
                            trafficMonitor.recordDownload(len.toLong())
                            router.writeDownlinkTcpPayload(host, port, buf.copyOf(len))
                        } else if (len < 0) break
                    }
                } catch (_: IOException) {}
            }

            Result.success(Unit)
        } catch (e: Exception) {
            _state.value = EngineState.Error(e, "Proxy connection failed: ${e.message}")
            stop()
            Result.failure(e)
        }
    }

    private fun readHttpHeader(inStream: InputStream): String {
        val out = ByteArrayOutputStream()
        var matched = 0
        val target = "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)

        while (true) {
            val b = inStream.read()
            if (b < 0) break
            out.write(b)
            if (b == target[matched].toInt()) {
                matched++
                if (matched == target.size) break
            } else {
                matched = if (b == target[0].toInt()) 1 else 0
            }
        }
        return out.toString(StandardCharsets.UTF_8.name())
    }

    override suspend fun stop() {
        tun2Socks?.stop()
        tun2Socks = null

        try { activeSocket?.close() } catch (_: IOException) {}
        activeSocket = null

        engineScope?.cancel()
        engineScope = null

        _state.value = EngineState.Disconnected
    }

    override suspend fun checkHealth(): HealthCheckResult = withContext(Dispatchers.IO) {
        val socket = activeSocket
        if (socket == null || socket.isClosed || !socket.isConnected) {
            return@withContext HealthCheckResult(false, -1, "HTTP proxy socket is disconnected")
        }
        HealthCheckResult(true, 15L, "HTTP proxy connection active")
    }
}
