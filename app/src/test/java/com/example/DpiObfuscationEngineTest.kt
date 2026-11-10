package com.example

import com.example.engine.DpiObfuscationEngine
import com.example.model.DpiTweakSettings
import com.example.model.TcpFragmentMode
import com.example.model.TunnelProtocol
import org.junit.Assert.*
import org.junit.Test

class DpiObfuscationEngineTest {

    @Test
    fun testPayloadExpansionTokens() {
        val template = "CONNECT [host_port] HTTP/1.1[crlf]Host: [host][crlf]User-Agent: [ua][crlf][crlf]"
        val expanded = DpiObfuscationEngine.expandPayload(
            payloadTemplate = template,
            targetHost = "speedtest.net",
            targetPort = 443,
            userAgent = "DpiClient/1.0"
        )

        assertTrue("Expanded payload should contain target host", expanded.contains("Host: speedtest.net"))
        assertTrue("Expanded payload should contain host_port", expanded.contains("CONNECT speedtest.net:443 HTTP/1.1"))
        assertTrue("Expanded payload should contain User-Agent", expanded.contains("User-Agent: DpiClient/1.0"))
        assertTrue("Expanded payload should substitute [crlf] with CRLF", expanded.contains("\r\n"))
    }

    @Test
    fun testFragmentationPlanCalculation() {
        val plan = DpiObfuscationEngine.calculateFragmentationPlan(
            sniDomain = "cloudflare.com",
            mode = TcpFragmentMode.SPLIT_AT_SNI,
            delayMs = 15L
        )

        assertEquals(TcpFragmentMode.SPLIT_AT_SNI, plan.mode)
        assertTrue("Split offset should be greater than 0", plan.splitOffset > 0)
        assertEquals("Chunks count should be 2 for split mode", 2, plan.chunkSizes.size)
        assertEquals("Total bytes in chunks should match record size", 517, plan.chunkSizes.sum())
    }

    @Test
    fun testSupportedTunnelProtocolsCount() {
        assertEquals("Should support all 13 protocols from specifications", 13, TunnelProtocol.entries.size)
        assertNotNull(TunnelProtocol.valueOf("VLESS"))
        assertNotNull(TunnelProtocol.valueOf("VMESS"))
        assertNotNull(TunnelProtocol.valueOf("SHADOWSOCKS"))
        assertNotNull(TunnelProtocol.valueOf("TROJAN"))
        assertNotNull(TunnelProtocol.valueOf("HYSTERIA2"))
        assertNotNull(TunnelProtocol.valueOf("WIREGUARD"))
        assertNotNull(TunnelProtocol.valueOf("SSH"))
        assertNotNull(TunnelProtocol.valueOf("SLOWDNS"))
    }
}
