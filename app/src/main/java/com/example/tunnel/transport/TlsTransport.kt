package com.example.tunnel.transport

import java.io.IOException
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*

/**
 * Transport layer handling TLS negotiation, SNI spoofing, and certificate validation.
 */
object TlsTransport {

    data class TlsConfig(
        val sniHost: String,
        val alpnProtocols: List<String> = listOf("http/1.1"),
        val allowInsecureCertificates: Boolean = false,
        val handshakeTimeoutMs: Int = 10000
    )

    private val insecureTrustManager = arrayOf<TrustManager>(
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    )

    /**
     * Upgrades an existing connected TCP socket to an SSLSocket with custom SNI and TLS parameters.
     */
    fun upgradeToTls(
        underlyingSocket: Socket,
        remoteHost: String,
        remotePort: Int,
        config: TlsConfig
    ): SSLSocket {
        val sslContext = SSLContext.getInstance("TLS")
        if (config.allowInsecureCertificates) {
            sslContext.init(null, insecureTrustManager, SecureRandom())
        } else {
            sslContext.init(null, null, SecureRandom())
        }

        val factory = sslContext.socketFactory
        val sslSocket = factory.createSocket(
            underlyingSocket,
            remoteHost,
            remotePort,
            true // autoClose underlying socket when SSLSocket is closed
        ) as SSLSocket

        // Set SNI Host
        val params = sslSocket.sslParameters
        try {
            val sni = SNIHostName(config.sniHost.trim())
            params.serverNames = listOf(sni)
        } catch (_: Exception) {}

        // Set ALPN if available (Android 10+ / API 29+)
        try {
            if (config.alpnProtocols.isNotEmpty()) {
                params.applicationProtocols = config.alpnProtocols.toTypedArray()
            }
        } catch (_: Throwable) {}

        sslSocket.sslParameters = params
        sslSocket.soTimeout = config.handshakeTimeoutMs

        // Execute explicit handshake
        try {
            sslSocket.startHandshake()
        } catch (e: SSLException) {
            sslSocket.close()
            throw IOException("TLS Handshake with ${config.sniHost} failed: ${e.message}", e)
        }

        return sslSocket
    }
}
