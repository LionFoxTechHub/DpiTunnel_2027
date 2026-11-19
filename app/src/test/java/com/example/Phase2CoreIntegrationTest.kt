package com.example

import com.example.model.CoreEngineType
import com.example.model.DpiTweakSettings
import com.example.model.TcpFragmentMode
import com.example.model.TunnelProfile
import com.example.model.TunnelProtocol
import com.example.tunnel.adapters.EngineRegistry
import com.example.tunnel.config.SingBoxConfigBuilder
import com.example.tunnel.config.XrayConfigBuilder
import com.example.tunnel.engine.SingBoxEngine
import com.example.tunnel.engine.XrayEngine
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Phase2CoreIntegrationTest {

    private val sampleVlessProfile = TunnelProfile(
        id = "test-vless",
        name = "Frankfurt VLESS Reality",
        protocol = TunnelProtocol.VLESS,
        serverHost = "de-fra.dpitunnel.net",
        serverPort = 443,
        uuidOrPassword = "7f2a1b9c-48d1-4ef3-9c02-e251a7e49121",
        countryCode = "DE",
        cityName = "Frankfurt",
        dpiTweak = DpiTweakSettings(
            sniHost = "speedtest.net",
            tcpFragmentMode = TcpFragmentMode.SPLIT_AT_SNI,
            httpPayload = "pbk=fakePubRealityKey123&sid=1a2b3c4d"
        )
    )

    private val sampleVmessProfile = TunnelProfile(
        id = "test-vmess",
        name = "Singapore VMess WS",
        protocol = TunnelProtocol.VMESS,
        serverHost = "sg-sin.dpitunnel.net",
        serverPort = 443,
        uuidOrPassword = "98a1c243-7bb2-4110-85f0-cc2019488a0b",
        countryCode = "SG",
        cityName = "Singapore",
        dpiTweak = DpiTweakSettings(
            sniHost = "cloudflare.com",
            fakeHttpRequest = true,
            httpPayload = "GET /tunnel HTTP/1.1[crlf]Host: bug.edge-cdn.net[crlf]Upgrade: websocket[crlf][crlf]"
        )
    )

    private val sampleTrojanProfile = TunnelProfile(
        id = "test-trojan",
        name = "London Trojan Stealth",
        protocol = TunnelProtocol.TROJAN,
        serverHost = "gb-lon.dpitunnel.net",
        serverPort = 443,
        uuidOrPassword = "trojan_secret_password_123",
        countryCode = "GB",
        cityName = "London",
        dpiTweak = DpiTweakSettings(
            sniHost = "microsoft.com"
        )
    )

    private val sampleShadowsocksProfile = TunnelProfile(
        id = "test-ss",
        name = "Tokyo Shadowsocks AEAD",
        protocol = TunnelProtocol.SHADOWSOCKS,
        serverHost = "jp-tyo.dpitunnel.net",
        serverPort = 8388,
        uuidOrPassword = "chacha20-ietf-poly1305:DpiSecretKey2026",
        countryCode = "JP",
        cityName = "Tokyo",
        dpiTweak = DpiTweakSettings(
            sniHost = "zoom.us"
        )
    )

    private val sampleVlessXhttpProfile = TunnelProfile(
        id = "test-vless-xhttp",
        name = "NYC VLESS XHTTP",
        protocol = TunnelProtocol.VLESS,
        serverHost = "us-nyc.dpitunnel.net",
        serverPort = 443,
        uuidOrPassword = "7f2a1b9c-48d1-4ef3-9c02-e251a7e49121",
        dpiTweak = DpiTweakSettings(
            sniHost = "cdn.cloudflare.com",
            httpPayload = "type=xhttp&path=/split-xhttp&Host: bughost.example.org"
        )
    )

    private val sampleGrpcProfile = TunnelProfile(
        id = "test-vless-grpc",
        name = "Zurich VLESS gRPC",
        protocol = TunnelProtocol.VLESS,
        serverHost = "ch-zrh.dpitunnel.net",
        serverPort = 443,
        uuidOrPassword = "7f2a1b9c-48d1-4ef3-9c02-e251a7e49121",
        dpiTweak = DpiTweakSettings(
            sniHost = "grpc.cloudflare.com",
            httpPayload = "type=grpc&serviceName=lionfox.TunnelService&security=tls"
        )
    )

    @Test
    fun testVlessSingBox113ConfigGeneration() {
        val configStr = SingBoxConfigBuilder.buildConfig(
            profile = sampleVlessProfile,
            verificationSocksPort = 10809
        )
        val json = JSONObject(configStr)

        // sing-box 1.13 DNS & rule actions schema
        val dns = json.getJSONObject("dns")
        assertEquals("dns-remote", dns.getString("final"))
        val dnsServers = dns.getJSONArray("servers")
        assertEquals("udp", dnsServers.getJSONObject(0).getString("type"))
        assertEquals("local", dnsServers.getJSONObject(dnsServers.length() - 1).getString("type"))

        val route = json.getJSONObject("route")
        assertEquals("dns-local", route.getString("default_domain_resolver"))
        val routeRules = route.getJSONArray("rules")
        assertEquals("sniff", routeRules.getJSONObject(0).getString("action"))
        assertEquals("hijack-dns", routeRules.getJSONObject(1).getString("action"))

        val inbounds = json.getJSONArray("inbounds")
        val tunInbound = inbounds.getJSONObject(0)
        assertEquals("tun", tunInbound.getString("type"))
        assertEquals("gvisor", tunInbound.getString("stack"))
        assertEquals("10.8.0.2/24", tunInbound.getJSONArray("address").getString(0))
        assertEquals("socks", inbounds.getJSONObject(1).getString("type"))
        assertEquals(10809, inbounds.getJSONObject(1).getInt("listen_port"))

        val outbounds = json.getJSONArray("outbounds")
        val proxyOutbound = outbounds.getJSONObject(0)
        assertEquals("vless", proxyOutbound.getString("type"))
        assertEquals("de-fra.dpitunnel.net", proxyOutbound.getString("server"))
        assertEquals(443, proxyOutbound.getInt("server_port"))
        assertEquals("7f2a1b9c-48d1-4ef3-9c02-e251a7e49121", proxyOutbound.getString("uuid"))

        val tls = proxyOutbound.getJSONObject("tls")
        assertTrue(tls.getBoolean("enabled"))
        assertEquals("speedtest.net", tls.getString("server_name"))
        val reality = tls.getJSONObject("reality")
        assertEquals("fakePubRealityKey123", reality.getString("public_key"))
        assertEquals("1a2b3c4d", reality.getString("short_id"))
    }

    @Test
    fun testVmessSingBoxConfigWithSeparateBugHostAndSni() {
        val configStr = SingBoxConfigBuilder.buildConfig(sampleVmessProfile)
        val json = JSONObject(configStr)

        val proxyOutbound = json.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("vmess", proxyOutbound.getString("type"))
        assertEquals("98a1c243-7bb2-4110-85f0-cc2019488a0b", proxyOutbound.getString("uuid"))

        // SNI must remain cloudflare.com while WS Host header is bug.edge-cdn.net
        assertEquals("cloudflare.com", proxyOutbound.getJSONObject("tls").getString("server_name"))

        val transport = proxyOutbound.getJSONObject("transport")
        assertEquals("ws", transport.getString("type"))
        assertEquals("/tunnel", transport.getString("path"))
        assertEquals("bug.edge-cdn.net", transport.getJSONObject("headers").getString("Host"))
    }

    @Test
    fun testTrojanAndShadowsocksAndGrpcSingBoxConfig() {
        val trojanJson = JSONObject(SingBoxConfigBuilder.buildConfig(sampleTrojanProfile))
        val trojanOutbound = trojanJson.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("trojan", trojanOutbound.getString("type"))
        assertEquals("trojan_secret_password_123", trojanOutbound.getString("password"))
        assertEquals("microsoft.com", trojanOutbound.getJSONObject("tls").getString("server_name"))

        val ssJson = JSONObject(SingBoxConfigBuilder.buildConfig(sampleShadowsocksProfile))
        val ssOutbound = ssJson.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("shadowsocks", ssOutbound.getString("type"))
        assertEquals("chacha20-ietf-poly1305", ssOutbound.getString("method"))
        assertEquals("DpiSecretKey2026", ssOutbound.getString("password"))

        val grpcJson = JSONObject(SingBoxConfigBuilder.buildConfig(sampleGrpcProfile))
        val grpcTransport = grpcJson.getJSONArray("outbounds").getJSONObject(0).getJSONObject("transport")
        assertEquals("grpc", grpcTransport.getString("type"))
        assertEquals("lionfox.TunnelService", grpcTransport.getString("service_name"))
    }

    @Test
    fun testXrayConfigGenerationIncludingTunInboundAndXhttp() {
        val vlessConfig = XrayConfigBuilder.buildConfig(
            profile = sampleVlessProfile,
            localSocksPort = 10808,
            useTunInbound = true,
            mtu = 1420
        )
        val json = JSONObject(vlessConfig)

        val inbounds = json.getJSONArray("inbounds")
        assertEquals(2, inbounds.length())
        val tunInbound = inbounds.getJSONObject(0)
        assertEquals("tun", tunInbound.getString("protocol"))
        assertEquals("tun0", tunInbound.getJSONObject("settings").getString("name"))
        assertEquals(1420, tunInbound.getJSONObject("settings").getInt("mtu"))

        val socksInbound = inbounds.getJSONObject(1)
        assertEquals("socks", socksInbound.getString("protocol"))
        assertEquals(10808, socksInbound.getInt("port"))

        val vlessOutbound = json.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("vless", vlessOutbound.getString("protocol"))
        val reality = vlessOutbound.getJSONObject("streamSettings").getJSONObject("realitySettings")
        assertEquals("speedtest.net", reality.getString("serverName"))
        assertEquals("fakePubRealityKey123", reality.getString("publicKey"))
        assertEquals("1a2b3c4d", reality.getString("shortId"))

        // XHTTP support on Xray-core
        val xhttpJson = JSONObject(XrayConfigBuilder.buildConfig(sampleVlessXhttpProfile))
        val xhttpStream = xhttpJson.getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
        assertEquals("xhttp", xhttpStream.getString("network"))
        val xhttpSettings = xhttpStream.getJSONObject("xhttpSettings")
        assertEquals("/split-xhttp", xhttpSettings.getString("path"))
        assertEquals("auto", xhttpSettings.getString("mode"))
    }

    @Test
    fun testXrayVmessTrojanShadowsocksAndV2RayMapping() {
        val vmessJson = JSONObject(XrayConfigBuilder.buildConfig(sampleVmessProfile))
        val vmessOutbound = vmessJson.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("vmess", vmessOutbound.getString("protocol"))
        val vmessStream = vmessOutbound.getJSONObject("streamSettings")
        assertEquals("ws", vmessStream.getString("network"))
        assertEquals("/tunnel", vmessStream.getJSONObject("wsSettings").getString("path"))
        assertEquals("bug.edge-cdn.net", vmessStream.getJSONObject("wsSettings").getJSONObject("headers").getString("Host"))
        assertEquals("cloudflare.com", vmessStream.getJSONObject("tlsSettings").getString("serverName"))

        // V2RAY protocol maps cleanly to VMess outbound on Xray
        val v2rayProfile = sampleVmessProfile.copy(id = "v2ray-1", protocol = TunnelProtocol.V2RAY)
        val v2rayJson = JSONObject(XrayConfigBuilder.buildConfig(v2rayProfile))
        assertEquals("vmess", v2rayJson.getJSONArray("outbounds").getJSONObject(0).getString("protocol"))

        val trojanJson = JSONObject(XrayConfigBuilder.buildConfig(sampleTrojanProfile))
        val trojanOutbound = trojanJson.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("trojan", trojanOutbound.getString("protocol"))
        assertEquals("microsoft.com", trojanOutbound.getJSONObject("streamSettings").getJSONObject("tlsSettings").getString("serverName"))

        val ssJson = JSONObject(XrayConfigBuilder.buildConfig(sampleShadowsocksProfile))
        val ssOutbound = ssJson.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("shadowsocks", ssOutbound.getString("protocol"))
        val ssServer = ssOutbound.getJSONObject("settings").getJSONArray("servers").getJSONObject(0)
        assertEquals("chacha20-ietf-poly1305", ssServer.getString("method"))
        assertEquals("DpiSecretKey2026", ssServer.getString("password"))
    }

    @Test
    fun testStrictValidationRejectsMissingCredentialsAndHardcodedFallbacks() {
        // Missing Reality pbk= must FAIL (never substitute a dummy public key!)
        val missingRealityKey = sampleVlessProfile.copy(
            name = "Frankfurt VLESS Reality",
            dpiTweak = DpiTweakSettings(sniHost = "speedtest.net", httpPayload = "security=reality")
        )
        assertTrue(SingBoxConfigBuilder.validateProfile(missingRealityKey).isFailure)
        assertTrue(XrayConfigBuilder.validateProfile(missingRealityKey).isFailure)

        // Missing sid= with valid pbk= must leave short_id/shortId empty "", never "01234567"
        val pbkWithoutSid = sampleVlessProfile.copy(
            dpiTweak = DpiTweakSettings(sniHost = "speedtest.net", httpPayload = "pbk=realPubKeyOnly")
        )
        val sbNoSid = JSONObject(SingBoxConfigBuilder.buildConfig(pbkWithoutSid))
        assertEquals(
            "",
            sbNoSid.getJSONArray("outbounds").getJSONObject(0)
                .getJSONObject("tls").getJSONObject("reality").getString("short_id")
        )

        // Invalid UUID must FAIL
        val badUuid = sampleVlessProfile.copy(uuidOrPassword = "not-a-valid-uuid")
        assertTrue(SingBoxConfigBuilder.validateProfile(badUuid).isFailure)
        assertTrue(XrayConfigBuilder.validateProfile(badUuid).isFailure)

        // Blank Trojan password must FAIL
        val badTrojan = sampleTrojanProfile.copy(uuidOrPassword = "   ")
        assertTrue(SingBoxConfigBuilder.validateProfile(badTrojan).isFailure)
        assertTrue(XrayConfigBuilder.validateProfile(badTrojan).isFailure)

        // Unsupported Shadowsocks cipher must FAIL
        val badCipherSs = sampleShadowsocksProfile.copy(uuidOrPassword = "rc4-md5:weakpass")
        assertTrue(SingBoxConfigBuilder.validateProfile(badCipherSs).isFailure)
        assertTrue(XrayConfigBuilder.validateProfile(badCipherSs).isFailure)

        // XHTTP rejected on sing-box
        assertTrue(SingBoxConfigBuilder.validateProfile(sampleVlessXhttpProfile).isFailure)

        // Unsupported protocols (SSH, HTTP, WIREGUARD) rejected instead of SOCKS fallback
        val sshProfile = sampleVlessProfile.copy(protocol = TunnelProtocol.SSH)
        assertTrue(SingBoxConfigBuilder.validateProfile(sshProfile).isFailure)
        assertTrue(XrayConfigBuilder.validateProfile(sshProfile).isFailure)

        val wgProfile = sampleVlessProfile.copy(protocol = TunnelProtocol.WIREGUARD)
        assertTrue(SingBoxConfigBuilder.validateProfile(wgProfile).isFailure)
        assertTrue(XrayConfigBuilder.validateProfile(wgProfile).isFailure)

        // CRLF injection rejected
        val crlfSni = sampleVlessProfile.copy(
            dpiTweak = DpiTweakSettings(sniHost = "bad.host\r\nX-Injected: 1", httpPayload = "pbk=key123")
        )
        assertTrue(XrayConfigBuilder.validateProfile(crlfSni).isFailure)
    }

    @Test
    fun testCredentialMaskingInLogs() {
        val sbConfig = SingBoxConfigBuilder.buildConfig(sampleVlessProfile)
        val sbMasked = SingBoxConfigBuilder.maskCredentials(sbConfig)
        assertFalse(sbMasked.contains("7f2a1b9c-48d1-4ef3-9c02-e251a7e49121"))
        assertFalse(sbMasked.contains("fakePubRealityKey123"))
        assertFalse(sbMasked.contains("1a2b3c4d"))
        assertTrue(sbMasked.contains("[PROTECTED_UUID]"))
        assertTrue(sbMasked.contains("[PROTECTED_KEY]"))

        val xrayConfig = XrayConfigBuilder.buildConfig(sampleVlessProfile)
        val xrayMasked = XrayConfigBuilder.maskCredentials(xrayConfig)
        assertFalse(xrayMasked.contains("7f2a1b9c-48d1-4ef3-9c02-e251a7e49121"))
        assertFalse(xrayMasked.contains("fakePubRealityKey123"))
        assertTrue(xrayMasked.contains("[PROTECTED_UUID]"))
        assertTrue(xrayMasked.contains("[PROTECTED_KEY]"))
    }

    @Test
    fun testExplicitEngineSelectionAndCapabilityReporting() {
        // Explicit SING_BOX
        val sbRes = EngineRegistry.resolveEngine(sampleVlessProfile.copy(preferredEngine = CoreEngineType.SING_BOX))
        assertTrue(sbRes.isSuccess)
        assertTrue(sbRes.getOrThrow() is SingBoxEngine)

        // Explicit XRAY_CORE
        val xrayRes = EngineRegistry.resolveEngine(sampleVlessProfile.copy(preferredEngine = CoreEngineType.XRAY_CORE))
        assertTrue(xrayRes.isSuccess)
        assertTrue(xrayRes.getOrThrow() is XrayEngine)

        // AUTO selects Xray for XHTTP and V2RAY
        val autoXhttp = EngineRegistry.resolveEngine(sampleVlessXhttpProfile.copy(preferredEngine = CoreEngineType.AUTO))
        assertTrue(autoXhttp.isSuccess)
        assertTrue(autoXhttp.getOrThrow() is XrayEngine)

        val autoV2ray = EngineRegistry.resolveEngine(
            sampleVmessProfile.copy(protocol = TunnelProtocol.V2RAY, preferredEngine = CoreEngineType.AUTO)
        )
        assertTrue(autoV2ray.isSuccess)
        assertTrue(autoV2ray.getOrThrow() is XrayEngine)

        // SING_BOX rejects XHTTP and V2RAY cleanly
        assertTrue(
            EngineRegistry.resolveEngine(sampleVlessXhttpProfile.copy(preferredEngine = CoreEngineType.SING_BOX)).isFailure
        )
        assertTrue(
            EngineRegistry.resolveEngine(
                sampleVmessProfile.copy(protocol = TunnelProtocol.V2RAY, preferredEngine = CoreEngineType.SING_BOX)
            ).isFailure
        )

        // WIREGUARD rejected explicitly (not registered as supported)
        assertTrue(
            EngineRegistry.resolveEngine(sampleVlessProfile.copy(protocol = TunnelProtocol.WIREGUARD)).isFailure
        )

        // Invalid Reality config rejected during resolveEngine
        val invalidReality = sampleVlessProfile.copy(
            dpiTweak = DpiTweakSettings(sniHost = "speedtest.net", httpPayload = "")
        )
        assertTrue(EngineRegistry.resolveEngine(invalidReality).isFailure)

        // Capability reporting
        val caps = EngineRegistry.getCapabilities()
        val sbCap = caps.first { it.coreType == CoreEngineType.SING_BOX }
        val xrayCap = caps.first { it.coreType == CoreEngineType.XRAY_CORE }
        assertTrue(sbCap.unsupportedFeatures.contains("xhttp"))
        assertTrue(xrayCap.supportedTransports.contains("xhttp"))
        assertFalse(sbCap.supportedProtocols.contains(TunnelProtocol.WIREGUARD))
        assertFalse(xrayCap.supportedProtocols.contains(TunnelProtocol.WIREGUARD))

        // Phase 1 regression check
        assertNotNull(EngineRegistry.getEngineForProtocol(TunnelProtocol.HTTP))
        assertNotNull(EngineRegistry.getEngineForProtocol(TunnelProtocol.SSH))
        assertNotNull(EngineRegistry.getEngineForProtocol(TunnelProtocol.SOCKS))
        assertNotNull(EngineRegistry.getEngineForProtocol(TunnelProtocol.SLOWDNS))
        assertNotNull(EngineRegistry.getEngineForProtocol(TunnelProtocol.OPENVPN))
        assertNotNull(EngineRegistry.getEngineForProtocol(TunnelProtocol.HYSTERIA2))
    }
}
