package com.example.tunnel.config

import com.example.model.TunnelProfile
import com.example.model.TunnelProtocol
import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts a [TunnelProfile] into sing-box (libbox v1.13+) JSON configuration.
 *
 * Enforces strict pre-generation validation (no hardcoded fallback UUIDs, passwords,
 * or Reality keys, and no silent fallback to SOCKS for unsupported protocols).
 */
object SingBoxConfigBuilder {

    fun validateProfile(profile: TunnelProfile): Result<ParsedProfileConfig> = runCatching {
        val parsed = CoreConfigValidator.validateAndParse(profile, allowV2RayAsVmess = false)
        if (parsed.transport == TransportType.XHTTP) {
            throw UnsupportedOperationException(
                "XHTTP transport is not supported by sing-box; select Xray-core instead"
            )
        }
        parsed
    }

    fun buildConfig(
        profile: TunnelProfile,
        tunFd: Int? = null,
        mtu: Int = 1400,
        dnsServers: List<String> = listOf("1.1.1.1", "8.8.8.8"),
        enableIpv6: Boolean = false,
        verificationSocksPort: Int = 0
    ): String {
        val parsed = validateProfile(profile).getOrThrow()
        val validMtu = mtu.coerceIn(1280, 9000)
        val sanitizedDns = dnsServers.map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf("1.1.1.1", "8.8.8.8") }

        val root = JSONObject()

        // 1. Log configuration
        val logObj = JSONObject().apply {
            put("level", "warn")
            put("timestamp", true)
        }
        root.put("log", logObj)

        // 2. DNS configuration (sing-box 1.12+/1.13 schema)
        val dnsServersArr = JSONArray()
        sanitizedDns.forEachIndexed { index, server ->
            dnsServersArr.put(
                JSONObject().apply {
                    put("tag", if (index == 0) "dns-remote" else "dns-remote-$index")
                    put("type", "udp")
                    put("server", server)
                    put("detour", "proxy")
                }
            )
        }
        dnsServersArr.put(
            JSONObject().apply {
                put("tag", "dns-local")
                put("type", "local")
            }
        )
        root.put(
            "dns",
            JSONObject().apply {
                put("servers", dnsServersArr)
                put("final", "dns-remote")
            }
        )

        // 3. Inbounds: Android TUN interface via gvisor stack (non-root)
        val inboundsArr = JSONArray()
        val tunAddresses = JSONArray().apply {
            put("10.8.0.2/24")
            if (enableIpv6) put("fd00:10:8::2/64")
        }
        val tunInbound = JSONObject().apply {
            put("type", "tun")
            put("tag", "tun-in")
            put("interface_name", "tun0")
            put("address", tunAddresses)
            put("mtu", validMtu)
            put("auto_route", false)
            put("strict_route", false)
            put("stack", "gvisor")
        }
        inboundsArr.put(tunInbound)

        if (verificationSocksPort in 1..65535) {
            inboundsArr.put(
                JSONObject().apply {
                    put("type", "socks")
                    put("tag", "socks-verify")
                    put("listen", "127.0.0.1")
                    put("listen_port", verificationSocksPort)
                }
            )
        }
        root.put("inbounds", inboundsArr)

        // 4. Outbounds: Primary proxy outbound + direct
        val outboundsArr = JSONArray().apply {
            put(buildOutbound(profile, parsed))
            put(JSONObject().apply {
                put("type", "direct")
                put("tag", "direct")
            })
        }
        root.put("outbounds", outboundsArr)

        // 5. Route configuration (sing-box 1.12+/1.13 rule actions)
        val rulesArr = JSONArray().apply {
            put(JSONObject().apply { put("action", "sniff") })
            put(JSONObject().apply {
                put("protocol", "dns")
                put("action", "hijack-dns")
            })
        }
        root.put(
            "route",
            JSONObject().apply {
                put("rules", rulesArr)
                put("final", "proxy")
                put("auto_detect_interface", true)
                put("default_domain_resolver", "dns-local")
            }
        )

        return root.toString(2)
    }

    private fun buildOutbound(profile: TunnelProfile, parsed: ParsedProfileConfig): JSONObject {
        val outbound = JSONObject()
        outbound.put("tag", "proxy")
        outbound.put("server", parsed.serverHost)
        outbound.put("server_port", parsed.serverPort)

        when (profile.protocol) {
            TunnelProtocol.VLESS -> {
                outbound.put("type", "vless")
                outbound.put("uuid", profile.uuidOrPassword.trim())
                outbound.put("flow", "")
                buildTlsConfig(parsed)?.let { outbound.put("tls", it) }
                buildTransportConfig(parsed)?.let { outbound.put("transport", it) }
            }
            TunnelProtocol.VMESS -> {
                outbound.put("type", "vmess")
                outbound.put("uuid", profile.uuidOrPassword.trim())
                outbound.put("security", "auto")
                outbound.put("alter_id", 0)
                buildTlsConfig(parsed)?.let { outbound.put("tls", it) }
                buildTransportConfig(parsed)?.let { outbound.put("transport", it) }
            }
            TunnelProtocol.TROJAN -> {
                outbound.put("type", "trojan")
                outbound.put("password", profile.uuidOrPassword.trim())
                buildTlsConfig(parsed, forceTls = true)?.let { outbound.put("tls", it) }
                buildTransportConfig(parsed)?.let { outbound.put("transport", it) }
            }
            TunnelProtocol.SHADOWSOCKS -> {
                outbound.put("type", "shadowsocks")
                outbound.put("method", parsed.ssMethod!!)
                outbound.put("password", parsed.ssPassword!!)
            }
            else -> {
                throw UnsupportedOperationException(
                    "Protocol ${profile.protocol.displayName} is not supported by sing-box"
                )
            }
        }

        return outbound
    }

    private fun buildTlsConfig(parsed: ParsedProfileConfig, forceTls: Boolean = false): JSONObject? {
        if (parsed.security == SecurityType.NONE && !forceTls) {
            return null
        }

        val tls = JSONObject().apply {
            put("enabled", true)
            put("server_name", parsed.sniHost)
            put("insecure", false)
        }

        if (parsed.alpnList.isNotEmpty()) {
            val alpnArr = JSONArray()
            parsed.alpnList.forEach { alpnArr.put(it) }
            tls.put("alpn", alpnArr)
        }

        if (parsed.security == SecurityType.REALITY) {
            val pbk = parsed.realityPublicKey
                ?: throw IllegalArgumentException("Reality public_key missing")
            tls.put(
                "reality",
                JSONObject().apply {
                    put("enabled", true)
                    put("public_key", pbk)
                    put("short_id", parsed.realityShortId)
                }
            )
            tls.put(
                "utls",
                JSONObject().apply {
                    put("enabled", true)
                    put("fingerprint", parsed.realityFingerprint)
                }
            )
        }

        return tls
    }

    private fun buildTransportConfig(parsed: ParsedProfileConfig): JSONObject? {
        return when (parsed.transport) {
            TransportType.WEBSOCKET -> JSONObject().apply {
                put("type", "ws")
                put("path", parsed.wsOrXhttpPath)
                put("headers", JSONObject().apply {
                    put("Host", parsed.wsHostHeader)
                })
            }
            TransportType.GRPC -> JSONObject().apply {
                put("type", "grpc")
                put("service_name", parsed.grpcServiceName)
            }
            TransportType.TCP -> null
            TransportType.XHTTP -> throw UnsupportedOperationException(
                "XHTTP transport is not supported by sing-box"
            )
        }
    }

    /**
     * Masks UUIDs, passwords, Reality public keys, and short IDs from configuration JSON before logging.
     */
    fun maskCredentials(configJson: String): String {
        return configJson
            .replace(Regex("\"uuid\":\\s*\"[^\"]*\""), "\"uuid\": \"[PROTECTED_UUID]\"")
            .replace(Regex("\"password\":\\s*\"[^\"]*\""), "\"password\": \"[PROTECTED_PASSWORD]\"")
            .replace(Regex("\"public_key\":\\s*\"[^\"]*\""), "\"public_key\": \"[PROTECTED_KEY]\"")
            .replace(Regex("\"short_id\":\\s*\"[^\"]*\""), "\"short_id\": \"[PROTECTED_ID]\"")
            .replace(Regex("\"private_key\":\\s*\"[^\"]*\""), "\"private_key\": \"[PROTECTED_KEY]\"")
    }
}
