package com.example

import com.example.model.DpiTweakSettings
import com.example.model.ProtocolPhaseStatus
import com.example.model.TunnelProfile
import com.example.model.TunnelProtocol
import com.example.tunnel.TrafficMonitor
import com.example.tunnel.adapters.EngineRegistry
import com.example.tunnel.engine.*
import com.example.tunnel.payload.PayloadBuilder
import com.example.tunnel.payload.PayloadBuilder.SplitMarker
import com.example.tunnel.repository.ConfigurationRepository
import com.example.tunnel.transport.TlsTransport
import com.example.tunnel.transport.WebSocketTransport
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class HttpTweakCompatibilityTest {

    @Test
    fun testAllExistingSupportedTokens() {
        val template = "[netData][crlf]Host: [host][crlf]Port: [port][crlf]HostPort: [host_port][crlf]" +
                "TargetHost: [target_host][crlf]TargetPort: [target_port][crlf]TargetHostPort: [target_host_port][crlf]" +
                "Protocol: [protocol][crlf]UA: [ua][crlf]Auth: [auth][crlf]" +
                "Raw: [raw][crlf]RealRaw: [real_raw][cr][lf]"

        val result = PayloadBuilder.buildPayload(
            template = template,
            targetHost = "proxy.remote.net",
            targetPort = 8443,
            bugHost = "bughost.cloudflare.com",
            userAgent = "DpiTunnelClient/2.0",
            protocol = "HTTP/1.1",
            proxyUsername = "testuser",
            proxyPassword = "testpassword"
        )

        // Verify [netData]
        assertTrue(result.contains("CONNECT proxy.remote.net:8443 HTTP/1.1"))
        // Verify [host], [port], [host_port] use bugHost
        assertTrue(result.contains("Host: bughost.cloudflare.com"))
        assertTrue(result.contains("Port: 8443"))
        assertTrue(result.contains("HostPort: bughost.cloudflare.com:8443"))
        // Verify [target_host], [target_port], [target_host_port] reflect actual proxy destination
        assertTrue(result.contains("TargetHost: proxy.remote.net"))
        assertTrue(result.contains("TargetPort: 8443"))
        assertTrue(result.contains("TargetHostPort: proxy.remote.net:8443"))
        // Verify [protocol], [ua]
        assertTrue(result.contains("Protocol: HTTP/1.1"))
        assertTrue(result.contains("UA: DpiTunnelClient/2.0"))
        // Verify [auth]
        assertTrue(result.contains("Auth: Basic dGVzdHVzZXI6dGVzdHBhc3N3b3Jk"))
        // Verify [crlf] and [cr][lf]
        assertTrue(result.contains("\r\n"))
        // Verify random tokens produced nonces
        assertTrue(result.contains("Raw: "))
        assertTrue(result.contains("RealRaw: "))
    }

    @Test
    fun testBugHostSeparatelyFromTargetHost() {
        val template = "CONNECT [target_host_port] [protocol][crlf]Host: [host][crlf]X-Target: [target_host][crlf][crlf]"

        // When Bug Host is specified
        val withBugHost = PayloadBuilder.buildPayload(
            template = template,
            targetHost = "192.168.1.100",
            targetPort = 443,
            bugHost = "speedtest.net"
        )
        assertTrue(withBugHost.contains("CONNECT 192.168.1.100:443 HTTP/1.1"))
        assertTrue(withBugHost.contains("Host: speedtest.net"))
        assertTrue(withBugHost.contains("X-Target: 192.168.1.100"))

        // When Bug Host is NOT specified (null or blank)
        val withoutBugHost = PayloadBuilder.buildPayload(
            template = template,
            targetHost = "192.168.1.100",
            targetPort = 443,
            bugHost = null
        )
        assertTrue(withoutBugHost.contains("CONNECT 192.168.1.100:443 HTTP/1.1"))
        assertTrue(withoutBugHost.contains("Host: 192.168.1.100"))
        assertTrue(withoutBugHost.contains("X-Target: 192.168.1.100"))
    }

    @Test
    fun testProxyAuthenticationGeneration() {
        val template = "Proxy-Authorization: [auth][crlf][crlf]"

        // With valid credentials
        val withAuth = PayloadBuilder.buildPayload(
            template = template,
            targetHost = "10.0.0.1",
            targetPort = 8080,
            proxyUsername = "admin",
            proxyPassword = "secretPassword123"
        )
        // admin:secretPassword123 in standard base64 is YWRtaW46c2VjcmV0UGFzc3dvcmQxMjM=
        assertTrue(withAuth.contains("Proxy-Authorization: Basic YWRtaW46c2VjcmV0UGFzc3dvcmQxMjM=\r\n\r\n"))

        // Without credentials
        val withoutAuth = PayloadBuilder.buildPayload(
            template = template,
            targetHost = "10.0.0.1",
            targetPort = 8080,
            proxyUsername = null,
            proxyPassword = null
        )
        assertTrue(withoutAuth.contains("Proxy-Authorization: \r\n\r\n"))
    }

    @Test
    fun testCrlfInjectionRejection() {
        val template = "CONNECT [host_port] HTTP/1.1[crlf]Host: [host][crlf][crlf]"

        // Injected targetHost
        try {
            PayloadBuilder.buildPayload(template, "target.com\r\nEvil-Header: injected", 80)
            fail("Expected IllegalArgumentException for CRLF in targetHost")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("CRLF"))
        }

        // Injected bugHost
        try {
            PayloadBuilder.buildPayload(template, "target.com", 80, bugHost = "bug.com\r\nInjected: true")
            fail("Expected IllegalArgumentException for CRLF in bugHost")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("CRLF"))
        }

        // Injected userAgent
        try {
            PayloadBuilder.buildPayload(template, "target.com", 80, userAgent = "UA\r\nInjected: true")
            fail("Expected IllegalArgumentException for CRLF in userAgent")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("CRLF"))
        }

        // Injected proxyUsername
        try {
            PayloadBuilder.buildPayload(template, "target.com", 80, proxyUsername = "user\r\nInjected: true")
            fail("Expected IllegalArgumentException for CRLF in proxyUsername")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("CRLF"))
        }
    }

    @Test
    fun testOrdinaryHttpHeaderTerminatorsDoNotCreateSplitBoundaries() {
        val template = "CONNECT [target_host_port] HTTP/1.1[crlf]Host: [host][crlf]User-Agent: [ua][crlf][crlf]"
        val segments = PayloadBuilder.buildSegments(
            template = template,
            targetHost = "dest.example.com",
            targetPort = 443
        )

        // Must produce exactly 1 segment! Header terminator \r\n\r\n must NOT split the request.
        assertEquals(1, segments.size)
        assertEquals(SplitMarker.NONE, segments[0].splitMarker)
        assertTrue(segments[0].text.endsWith("\r\n\r\n"))
    }

    @Test
    fun testExplicitSplitAndDelaySplitBoundaries() {
        val template = "CONNECT [target_host_port] [protocol][crlf]Host: [host][crlf][split]" +
                "GET /path [protocol][crlf]Host: [host][crlf][delay_split]" +
                "X-Custom: verified[crlf][crlf]"

        val segments = PayloadBuilder.buildSegments(
            template = template,
            targetHost = "server.net",
            targetPort = 8080,
            bugHost = "bughost.com"
        )

        assertEquals("Should produce exactly 3 structured segments", 3, segments.size)

        // Segment 1 (terminated by [split])
        assertEquals(SplitMarker.INSTANT, segments[0].splitMarker)
        assertTrue(segments[0].text.startsWith("CONNECT server.net:8080 HTTP/1.1\r\n"))
        assertTrue(segments[0].text.endsWith("Host: bughost.com\r\n"))

        // Segment 2 (terminated by [delay_split])
        assertEquals(SplitMarker.DELAYED, segments[1].splitMarker)
        assertTrue(segments[1].text.startsWith("GET /path HTTP/1.1\r\n"))

        // Segment 3 (final segment ending with \r\n\r\n)
        assertEquals(SplitMarker.NONE, segments[2].splitMarker)
        assertEquals("X-Custom: verified\r\n\r\n", segments[2].text)

        // Ensure order is strictly preserved
        val full = segments.joinToString("") { it.text }
        assertTrue(full.startsWith("CONNECT server.net:8080"))
        assertTrue(full.contains("GET /path"))
        assertTrue(full.endsWith("X-Custom: verified\r\n\r\n"))
    }

    @Test
    fun testDeterministicPayloadOutput() {
        val template = "GET / HTTP/1.1[crlf]Host: [host][crlf]Port: [port][crlf][crlf]"

        val runs = (1..10).map {
            PayloadBuilder.buildPayload(template, "static.target.com", 80)
        }

        // All runs must produce byte-for-byte identical output when no random tokens are present
        val first = runs[0]
        runs.forEach { run ->
            assertEquals(first, run)
        }
    }

    @Test
    fun testRandomNonceTokens() {
        val template = "Sec-WebSocket-Key: [raw][crlf]Real: [real_raw][crlf][crlf]"
        val result = PayloadBuilder.buildPayload(template, "127.0.0.1", 80)

        // 16-byte random bytes base64 encoded is 24 characters with '=' padding
        val regex = "Sec-WebSocket-Key: ([A-Za-z0-9+/]{22}==)".toRegex()
        val match = regex.find(result)
        assertNotNull("Expected valid 16-byte Base64 nonce", match)
    }

    @Test
    fun testTokenValidationAndUnsupportedTokens() {
        // Valid template
        val valid = PayloadBuilder.validatePayload("GET / HTTP/1.1[crlf]Host: [host][crlf][crlf]")
        assertTrue(valid.isValid)
        assertTrue(valid.issues.isEmpty())

        // Unclosed token
        val unclosed = PayloadBuilder.validatePayload("GET / HTTP/1.1[crlf]Host: [host")
        assertFalse(unclosed.isValid)
        assertTrue(unclosed.issues.any { it.contains("Unclosed") })

        // Unexpected closing bracket
        val unexpected = PayloadBuilder.validatePayload("GET / HTTP/1.1[crlf]Host: host][crlf]")
        assertFalse(unexpected.isValid)
        assertTrue(unexpected.issues.any { it.contains("Unexpected closing bracket") })

        // Empty token []
        val emptyToken = PayloadBuilder.validatePayload("GET / HTTP/1.1[crlf]Host: [][crlf]")
        assertFalse(emptyToken.isValid)
        assertTrue(emptyToken.issues.any { it.contains("Malformed empty token") })

        // Unsupported token
        val unsupported = PayloadBuilder.validatePayload("GET / HTTP/1.1[crlf]Host: [unsupported_token][crlf]")
        assertFalse(unsupported.isValid)
        assertTrue(unsupported.issues.any { it.contains("Unsupported token '[unsupported_token]'") })

        // Empty/blank payload
        val empty = PayloadBuilder.validatePayload("   ")
        assertFalse(empty.isValid)
        assertTrue(empty.issues.any { it.contains("cannot be empty") })
    }

    @Test
    fun testTargetHostAndPortValidation() {
        // Invalid target ports
        val portZero = PayloadBuilder.validateTarget("127.0.0.1", 0)
        assertFalse(portZero.isValid)

        val portTooLarge = PayloadBuilder.validateTarget("127.0.0.1", 70000)
        assertFalse(portTooLarge.isValid)

        // Blank target host
        val blankHost = PayloadBuilder.validateTarget("   ", 80)
        assertFalse(blankHost.isValid)

        // Whitespace in target host
        val spaceHost = PayloadBuilder.validateTarget("evil.com target.com", 80)
        assertFalse(spaceHost.isValid)

        // Valid host and port
        val validTarget = PayloadBuilder.validateTarget("127.0.0.1", 8080)
        assertTrue(validTarget.isValid)
    }

    @Test
    fun testDpiTweakSettingsValidationAndSanitization() {
        // Valid settings
        val validSettings = DpiTweakSettings()
        val validRes = validSettings.validate()
        assertTrue(validRes.isValid)

        // Settings with malformed payload
        val invalidSettings = DpiTweakSettings(
            httpPayload = "GET / HTTP/1.1[crlf]Host: [unsupported_foo][crlf][crlf]"
        )
        val invalidRes = invalidSettings.validate()
        assertFalse(invalidRes.isValid)
        assertTrue(invalidRes.issues.any { it.contains("unsupported_foo") })

        // Sanitization test
        val (host, port) = validSettings.sanitizeTarget("   example.com   ", 443)
        assertEquals("example.com", host)
        assertEquals(443, port)

        try {
            validSettings.sanitizeTarget("evil.com\r\ninjected: true", 443)
            fail("Expected exception for CRLF in sanitizeTarget")
        } catch (_: IllegalArgumentException) {}
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

        val vlessEngine = EngineRegistry.getEngineForProtocol(TunnelProtocol.VLESS)
        assertNotNull("VLESS resolves to Phase 2 core engine", vlessEngine)

        val vmessEngine = EngineRegistry.getEngineForProtocol(TunnelProtocol.VMESS)
        assertNotNull("VMess resolves to Phase 2 core engine", vmessEngine)
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
