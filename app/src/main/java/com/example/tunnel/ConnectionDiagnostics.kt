package com.example.tunnel

import com.example.tunnel.payload.PayloadBuilder
import com.example.tunnel.transport.TlsTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets

object ConnectionDiagnostics {

    data class DnsDiagnostic(
        val hostname: String,
        val isSuccessful: Boolean,
        val resolvedIps: List<String>,
        val latencyMs: Long,
        val errorMessage: String? = null
    )

    data class TcpDiagnostic(
        val host: String,
        val port: Int,
        val isConnected: Boolean,
        val latencyMs: Long,
        val localAddress: String? = null,
        val errorMessage: String? = null
    )

    data class ProxyDiagnostic(
        val proxyHost: String,
        val proxyPort: Int,
        val isTunnelEstablished: Boolean,
        val httpStatusCode: Int,
        val responseHeaders: Map<String, String>,
        val latencyMs: Long,
        val errorMessage: String? = null
    )

    data class TlsDiagnostic(
        val host: String,
        val port: Int,
        val sniHost: String,
        val isHandshakeSuccessful: Boolean,
        val cipherSuite: String? = null,
        val protocolVersion: String? = null,
        val latencyMs: Long,
        val errorMessage: String? = null
    )

    suspend fun testDns(hostname: String, timeoutMs: Int = 3000): DnsDiagnostic = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val addresses = InetAddress.getAllByName(hostname)
            val latency = System.currentTimeMillis() - start
            DnsDiagnostic(
                hostname = hostname,
                isSuccessful = true,
                resolvedIps = addresses.map { it.hostAddress ?: "" }.filter { it.isNotEmpty() },
                latencyMs = latency
            )
        } catch (e: UnknownHostException) {
            DnsDiagnostic(
                hostname = hostname,
                isSuccessful = false,
                resolvedIps = emptyList(),
                latencyMs = System.currentTimeMillis() - start,
                errorMessage = "Host not found: ${e.message}"
            )
        } catch (e: Exception) {
            DnsDiagnostic(
                hostname = hostname,
                isSuccessful = false,
                resolvedIps = emptyList(),
                latencyMs = System.currentTimeMillis() - start,
                errorMessage = "DNS error: ${e.message}"
            )
        }
    }

    suspend fun testTcp(
        host: String,
        port: Int,
        timeoutMs: Int = 4000,
        protector: SocketProtector? = null
    ): TcpDiagnostic = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val socket = Socket()
        protector?.protect(socket)

        try {
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            val latency = System.currentTimeMillis() - start
            val localAddr = socket.localSocketAddress?.toString()
            socket.close()

            TcpDiagnostic(
                host = host,
                port = port,
                isConnected = true,
                latencyMs = latency,
                localAddress = localAddr
            )
        } catch (e: UnknownHostException) {
            TcpDiagnostic(
                host = host,
                port = port,
                isConnected = false,
                latencyMs = System.currentTimeMillis() - start,
                errorMessage = "Cannot resolve host: $host"
            )
        } catch (e: SocketTimeoutException) {
            TcpDiagnostic(
                host = host,
                port = port,
                isConnected = false,
                latencyMs = System.currentTimeMillis() - start,
                errorMessage = "Connection timed out after ${timeoutMs}ms"
            )
        } catch (e: Exception) {
            TcpDiagnostic(
                host = host,
                port = port,
                isConnected = false,
                latencyMs = System.currentTimeMillis() - start,
                errorMessage = "Connection refused or reset: ${e.message}"
            )
        } finally {
            try { socket.close() } catch (_: IOException) {}
        }
    }

    suspend fun testProxyPayload(
        proxyHost: String,
        proxyPort: Int,
        targetHost: String,
        targetPort: Int,
        payloadTemplate: String,
        bugHost: String? = null,
        timeoutMs: Int = 5000,
        protector: SocketProtector? = null
    ): ProxyDiagnostic = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val socket = Socket()
        protector?.protect(socket)

        try {
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(proxyHost, proxyPort), timeoutMs)

            val payload = PayloadBuilder.buildPayload(
                template = payloadTemplate,
                targetHost = targetHost,
                targetPort = targetPort,
                bugHost = bugHost
            )

            val outStream = socket.getOutputStream()
            outStream.write(payload.toByteArray(StandardCharsets.UTF_8))
            outStream.flush()

            val inStream = socket.getInputStream()
            val headerText = readHeader(inStream)
            val latency = System.currentTimeMillis() - start

            val lines = headerText.split("\r\n")
            val statusLine = lines.getOrNull(0) ?: ""
            val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 0

            val headers = mutableMapOf<String, String>()
            for (i in 1 until lines.size) {
                val colon = lines[i].indexOf(':')
                if (colon > 0) {
                    headers[lines[i].substring(0, colon).trim().lowercase()] = lines[i].substring(colon + 1).trim()
                }
            }

            val isEstablished = statusCode in 200..299 || statusCode == 101

            ProxyDiagnostic(
                proxyHost = proxyHost,
                proxyPort = proxyPort,
                isTunnelEstablished = isEstablished,
                httpStatusCode = statusCode,
                responseHeaders = headers,
                latencyMs = latency,
                errorMessage = if (!isEstablished) "Proxy returned HTTP status $statusCode ($statusLine)" else null
            )
        } catch (e: Exception) {
            ProxyDiagnostic(
                proxyHost = proxyHost,
                proxyPort = proxyPort,
                isTunnelEstablished = false,
                httpStatusCode = 0,
                responseHeaders = emptyMap(),
                latencyMs = System.currentTimeMillis() - start,
                errorMessage = "Proxy error: ${e.message}"
            )
        } finally {
            try { socket.close() } catch (_: IOException) {}
        }
    }

    suspend fun testTls(
        host: String,
        port: Int,
        sniHost: String,
        timeoutMs: Int = 6000,
        protector: SocketProtector? = null
    ): TlsDiagnostic = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val socket = Socket()
        protector?.protect(socket)

        try {
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(host, port), timeoutMs)

            val sslSocket = TlsTransport.upgradeToTls(
                underlyingSocket = socket,
                remoteHost = host,
                remotePort = port,
                config = TlsTransport.TlsConfig(sniHost = sniHost, handshakeTimeoutMs = timeoutMs)
            )

            val latency = System.currentTimeMillis() - start
            val cipher = sslSocket.session.cipherSuite
            val protocol = sslSocket.session.protocol
            sslSocket.close()

            TlsDiagnostic(
                host = host,
                port = port,
                sniHost = sniHost,
                isHandshakeSuccessful = true,
                cipherSuite = cipher,
                protocolVersion = protocol,
                latencyMs = latency
            )
        } catch (e: Exception) {
            TlsDiagnostic(
                host = host,
                port = port,
                sniHost = sniHost,
                isHandshakeSuccessful = false,
                latencyMs = System.currentTimeMillis() - start,
                errorMessage = "TLS handshake failed: ${e.message}"
            )
        } finally {
            try { socket.close() } catch (_: IOException) {}
        }
    }

    private fun readHeader(inStream: java.io.InputStream): String {
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
}
