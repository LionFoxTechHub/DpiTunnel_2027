package com.example

import com.example.model.DpiTweakSettings
import com.example.model.ProtocolPhaseStatus
import com.example.model.TunnelProfile
import com.example.model.TunnelProtocol
import com.example.tunnel.TrafficMonitor
import com.example.tunnel.adapters.EngineRegistry
import com.example.tunnel.engine.*
import com.example.tunnel.payload.PayloadBuilder
import com.example.tunnel.repository.ConfigurationRepository
import com.example.tunnel.transport.TlsTransport
import com.example.tunnel.transport.WebSocketTransport
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class HttpTweakCompatibilityTest {

    @Test
    fun testPayloadSubstitution() {
        val template = "CONNECT [host_port] [protocol][crlf]Host: [host][crlf]User-Agent: [ua][crlf]Proxy-Authorization: [auth][crlf][crlf]"
        val result = PayloadBuilder.buildPayload(
            template = template,
            targetHost = "1.2.3.4",
            targetPort = 8080,
            bugHost = "speedtest.net",
            userAgent = "DpiTunnelClient/1.0",
            protocol = "HTTP/1.1",
            proxyUsername = "admin",
            proxyPassword = "secret"
        )

        assertTrue(result.contains("CONNECT speedtest.net:8080 HTTP/1.1"))
        assertTrue(result.contains("Host: speedtest.net"))
        assertTrue(result.contains("User-Agent: DpiTunnelClient/1.0"))
        assertTrue(result.contains("Proxy-Authorization: Basic "))
        assertTrue(result.contains("\r\n"))
    }

    @Test
    fun testPayloadValidation() {
        val valid = PayloadBuilder.validatePayload("GET / HTTP/1.1[crlf]Host: [host][crlf][crlf]")
        assertTrue(valid.isValid)

        val unclosedBracket = PayloadBuilder.validatePayload("GET / HTTP/1.1[crlf]Host: [host")
        assertFalse(unclosedBracket.isValid)
        assertTrue(unclosedBracket.issues.any { it.contains("unclosed") })

        val empty = PayloadBuilder.validatePayload("   ")
        assertFalse(empty.isValid)
    }

    @Test
    fun testPayloadSegmentation() {
        val template = "CONNECT [host_port] HTTP/1.1[crlf]Host: [host][split]GET / HTTP/1.1[crlf]Host: [host][crlf][crlf]"
        val evaluated = PayloadBuilder.buildPayload(template, "example.com", 443)
        val segments = PayloadBuilder.getPayloadSegments(evaluated)

        assertEquals("Should split into 2 TCP write segments", 2, segments.size)
        assertTrue(String(segments[0], StandardCharsets.UTF_8).contains("CONNECT"))
        assertTrue(String(segments[1], StandardCharsets.UTF_8).contains("GET"))
    }

    @Test
    fun testWebSocketFrameEncodingAndDecoding() {
        val testData = "Hello DpiTunnel WebSocket!".toByteArray(StandardCharsets.UTF_8)
        val encodedFrame = WebSocketTransport.encodeBinaryFrame(testData)

        assertNotNull(encodedFrame)
        assertTrue(encodedFrame.size > testData.size)
        // Opcode 0x82 (FIN + Binary)
        assertEquals(0x82.toByte(), encodedFrame[0])

        // Verify decoding
        val inStream = ByteArrayInputStream(encodedFrame)
        val decoded = WebSocketTransport.decodeFrame(inStream)
        assertNotNull(decoded)
        assertEquals(String(testData, StandardCharsets.UTF_8), String(decoded!!, StandardCharsets.UTF_8))
    }

    @Test
    fun testTlsConfigParameters() {
        val config = TlsTransport.TlsConfig(
            sniHost = "cloudflare.com",
            alpnProtocols = listOf("h2", "http/1.1"),
            allowInsecureCertificates = false
        )
        assertEquals("cloudflare.com", config.sniHost)
        assertEquals(2, config.alpnProtocols.size)
        assertFalse(config.allowInsecureCertificates)
    }

    @Test
    fun testTrafficMonitorZeroSimulation() {
        val monitor = TrafficMonitor()
        assertEquals(0L, monitor.totalUploadedBytes)
        assertEquals(0L, monitor.totalDownloadedBytes)

        val (up0, down0) = monitor.tick()
        assertEquals(0L, up0)
        assertEquals(0L, down0)

        // Record 1200 bytes upload and 3400 bytes download
        monitor.recordUpload(1200L)
        monitor.recordDownload(3400L)

        assertEquals(1200L, monitor.totalUploadedBytes)
        assertEquals(3400L, monitor.totalDownloadedBytes)

        val (up1, down1) = monitor.tick()
        assertEquals(1200L, up1)
        assertEquals(3400L, down1)

        // Idle tick returns 0 B/s without simulation
        val (upIdle, downIdle) = monitor.tick()
        assertEquals(0L, upIdle)
        assertEquals(0L, downIdle)
        assertEquals(1200L, monitor.totalUploadedBytes)
        assertEquals(3400L, monitor.totalDownloadedBytes)
    }

    @Test
    fun testEngineRegistryPhaseResolution() {
        // Phase 1 active protocols must resolve to dedicated engines
        val httpEngine = EngineRegistry.getEngineForProtocol(TunnelProtocol.HTTP)
        assertNotNull(httpEngine)
        assertTrue(httpEngine is HttpProxyEngine)

        val sshEngine = EngineRegistry.getEngineForProtocol(TunnelProtocol.SSH)
        assertNotNull(sshEngine)
        assertTrue(sshEngine is SshEngine)

        val socksEngine = EngineRegistry.getEngineForProtocol(TunnelProtocol.SOCKS)
        assertNotNull(socksEngine)
        assertTrue(socksEngine is SocksEngine)

        val dnsEngine = EngineRegistry.getEngineForProtocol(TunnelProtocol.SLOWDNS)
        assertNotNull(dnsEngine)
        assertTrue(dnsEngine is DnsTunnelEngine)

        val ovpnEngine = EngineRegistry.getEngineForProtocol(TunnelProtocol.OPENVPN)
        assertNotNull(ovpnEngine)
        assertTrue(ovpnEngine is OpenVpnEngine)

        val hy2Engine = EngineRegistry.getEngineForProtocol(TunnelProtocol.HYSTERIA2)
        assertNotNull(hy2Engine)
        assertTrue(hy2Engine is HysteriaEngine)

        // Phase 2 unintegrated protocols must return null so app never falsely claims connection
        val vlessEngine = EngineRegistry.getEngineForProtocol(TunnelProtocol.VLESS)
        assertNull("VLESS must not resolve to a stub engine in Phase 1", vlessEngine)

        val vmessEngine = EngineRegistry.getEngineForProtocol(TunnelProtocol.VMESS)
        assertNull("VMess must not resolve to a stub engine in Phase 1", vmessEngine)
    }

    @Test
    fun testConfigurationProfileValidation() {
        val validProfile = TunnelProfile(
            name = "Valid Server",
            protocol = TunnelProtocol.SSH,
            serverHost = "10.0.0.1",
            serverPort = 22
        )
        val validResult = ConfigurationRepository.validateProfile(validProfile)
        assertTrue(validResult.isValid)

        val invalidPort = validProfile.copy(serverPort = 99999)
        val invalidPortResult = ConfigurationRepository.validateProfile(invalidPort)
        assertFalse(invalidPortResult.isValid)

        val blankHost = validProfile.copy(serverHost = "   ")
        val blankHostResult = ConfigurationRepository.validateProfile(blankHost)
        assertFalse(blankHostResult.isValid)
    }
}
