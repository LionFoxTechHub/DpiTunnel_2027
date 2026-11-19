package com.example.tunnel.config

import com.example.model.TunnelProfile
import com.example.model.TunnelProtocol
import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts a [TunnelProfile] into Xray-core (libxray v26.x) JSON configuration.
 *
 * Supports:
 * - Native TUN inbound (`protocol: "tun"`) consuming `LibXray.setTunFd(fd)` via gVisor
 * - Optional local SOCKS5 inbound (`socks-in`) for verification or explicit port tests
 * - Outbounds: VLESS, VMess (including V2RAY profile mapping), Trojan, Shadowsocks
 * - Transports: TCP, WebSocket, gRPC, and XHTTP (`xhttpSettings`)
 * - Security: None, TLS, Reality (with strict parameter validation; zero hardcoded fallback keys)
 */
object XrayConfigBuilder {

    fun validateProfile(profile: TunnelProfile): Result<ParsedProfileConfig> = runCatching {
        CoreConfigValidator.validateAndParse(profile, allowV2RayAsVmess = true)
    }

    fun buildConfig(
        profile: TunnelProfile,
        localSocksPort: Int = 10808,
        dnsServers: List<String> = listOf("1.1.1.1", "8.8.8.8"),
        useTunInbound: Boolean = false,
        mtu: Int = 1400
    ): String {
        val parsed = validateProfile(profile).getOrThrow()
        val validMtu = mtu.coerceIn(1280, 9000)
        val sanitizedDns = dnsServers.map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf("1.1.1.1", "8.8.8.8") }

        val root = JSONObject()

        // 1. Log configuration
        root.put("log", JSONObject().apply {
            put("loglevel", "warning")
        })

        // 2. Inbounds: Native TUN inbound (for VPN runtime) and/or SOCKS5 inbound
        val inboundsArr = JSONArray()
        val sniffingObj = JSONObject().apply {
            put("enabled", true)
            put("destOverride", JSONArray().apply {
                put("http")
                put("tls")
                put("quic")
            })
        }

        if (useTunInbound) {
            inboundsArr.put(
                JSONObject().apply {
                    put("tag", "tun-in")
                    put("protocol", "tun")
                    put("settings", JSONObject().apply {
                        put("name", "tun0")
                        put("mtu", validMtu)
                        put("userLevel", 0)
                    })
                    put("sniffing", sniffingObj)
                }
            )
        }

        if (!useTunInbound || localSocksPort in 1..65535) {
            val socksPort = if (localSocksPort in 1..65535) localSocksPort else 10808
            inboundsArr.put(
                JSONObject().apply {
                    put("tag", "socks-in")
                    put("port", socksPort)
                    put("listen", "127.0.0.1")
                    put("protocol", "socks")
                    put("settings", JSONObject().apply {
                        put("auth", "noauth")
                        put("udp", true)
                    })
                    put("sniffing", sniffingObj)
                }
            )
        }
        root.put("inbounds", inboundsArr)

        // 3. Outbounds: Primary proxy outbound + freedom direct
        val outboundsArr = JSONArray().apply {
            put(buildOutbound(profile, parsed))
            put(JSONObject().apply {
                put("tag", "direct")
                put("protocol", "freedom")
                put("settings", JSONObject())
            })
        }
        root.put("outbounds", outboundsArr)

        // 4. DNS configuration
        val dnsServersArr = JSONArray()
        sanitizedDns.forEach { dnsServersArr.put(it) }
        root.put("dns", JSONObject().apply {
            put("servers", dnsServersArr)
        })

        // 5. Routing: Route all inbound TCP/UDP traffic via primary proxy outbound
        root.put("routing", JSONObject().apply {
            put("domainStrategy", "AsIs")
            put("rules", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "field")
                    put("network", "tcp,udp")
                    put("outboundTag", "proxy")
                })
            })
        })

        return root.toString(2)
    }

    private fun buildOutbound(profile: TunnelProfile, parsed: ParsedProfileConfig): JSONObject {
        val outbound = JSONObject()
        outbound.put("tag", "proxy")

        when (profile.protocol) {
            TunnelProtocol.VLESS -> {
                outbound.put("protocol", "vless")
                outbound.put("settings", JSONObject().apply {
                    put("vnext", JSONArray().apply {
                        put(JSONObject().apply {
                            put("address", parsed.serverHost)
                            put("port", parsed.serverPort)
                            put("users", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("id", profile.uuidOrPassword.trim())
                                    put("encryption", "none")
                                })
                            })
                        })
                    })
                })
                outbound.put("streamSettings", buildStreamSettings(parsed))
            }
            TunnelProtocol.VMESS, TunnelProtocol.V2RAY -> {
                outbound.put("protocol", "vmess")
                outbound.put("settings", JSONObject().apply {
                    put("vnext", JSONArray().apply {
                        put(JSONObject().apply {
                            put("address", parsed.serverHost)
                            put("port", parsed.serverPort)
                            put("users", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("id", profile.uuidOrPassword.trim())
                                    put("alterId", 0)
                                    put("security", "auto")
                                })
                            })
                        })
                    })
                })
                outbound.put("streamSettings", buildStreamSettings(parsed))
            }
            TunnelProtocol.TROJAN -> {
                outbound.put("protocol", "trojan")
                outbound.put("settings", JSONObject().apply {
                    put("servers", JSONArray().apply {
                        put(JSONObject().apply {
                            put("address", parsed.serverHost)
                            put("port", parsed.serverPort)
                            put("password", profile.uuidOrPassword.trim())
                        })
                    })
                })
                outbound.put("streamSettings", buildStreamSettings(parsed, forceTls = true))
            }
            TunnelProtocol.SHADOWSOCKS -> {
                outbound.put("protocol", "shadowsocks")
                outbound.put("settings", JSONObject().apply {
                    put("servers", JSONArray().apply {
                        put(JSONObject().apply {
                            put("address", parsed.serverHost)
                            put("port", parsed.serverPort)
                            put("method", parsed.ssMethod!!)
                            put("password", parsed.ssPassword!!)
                        })
                    })
                })
            }
            else -> {
                throw UnsupportedOperationException(
                    "Protocol ${profile.protocol.displayName} is not supported by Xray-core"
                )
            }
        }

        return outbound
    }

    private fun buildStreamSettings(parsed: ParsedProfileConfig, forceTls: Boolean = false): JSONObject {
        val stream = JSONObject()
        stream.put("network", parsed.transport.id)

        val effectiveSecurity = if (forceTls && parsed.security == SecurityType.NONE) {
            SecurityType.TLS
        } else {
            parsed.security
        }
        stream.put("security", effectiveSecurity.id)

        when (effectiveSecurity) {
            SecurityType.REALITY -> {
                val pbk = parsed.realityPublicKey
                    ?: throw IllegalArgumentException("Reality publicKey missing")
                stream.put("realitySettings", JSONObject().apply {
                    put("serverName", parsed.sniHost)
                    put("fingerprint", parsed.realityFingerprint)
                    put("show", false)
                    put("publicKey", pbk)
                    put("shortId", parsed.realityShortId)
                })
            }
            SecurityType.TLS -> {
                stream.put("tlsSettings", JSONObject().apply {
                    put("serverName", parsed.sniHost)
                    put("allowInsecure", false)
                    if (parsed.alpnList.isNotEmpty()) {
                        put("alpn", JSONArray().apply {
                            parsed.alpnList.forEach { put(it) }
                        })
                    }
                })
            }
            SecurityType.NONE -> Unit
        }

        when (parsed.transport) {
            TransportType.WEBSOCKET -> {
                stream.put("wsSettings", JSONObject().apply {
                    put("path", parsed.wsOrXhttpPath)
                    put("headers", JSONObject().apply {
                        put("Host", parsed.wsHostHeader)
                    })
                })
            }
            TransportType.GRPC -> {
                stream.put("grpcSettings", JSONObject().apply {
                    put("serviceName", parsed.grpcServiceName)
                    put("multiMode", false)
                })
            }
            TransportType.XHTTP -> {
                stream.put("xhttpSettings", JSONObject().apply {
                    put("path", parsed.wsOrXhttpPath)
                    put("host", parsed.wsHostHeader)
                    put("mode", "auto")
                })
            }
            TransportType.TCP -> Unit
        }

        return stream
    }

    /**
     * Masks UUIDs, passwords, Reality keys, and short IDs before debug logging.
     */
    fun maskCredentials(configJson: String): String {
        return configJson
            .replace(Regex("\"id\":\\s*\"[^\"]*\""), "\"id\": \"[PROTECTED_UUID]\"")
            .replace(Regex("\"password\":\\s*\"[^\"]*\""), "\"password\": \"[PROTECTED_PASSWORD]\"")
            .replace(Regex("\"publicKey\":\\s*\"[^\"]*\""), "\"publicKey\": \"[PROTECTED_KEY]\"")
            .replace(Regex("\"shortId\":\\s*\"[^\"]*\""), "\"shortId\": \"[PROTECTED_ID]\"")
            .replace(Regex("\"privateKey\":\\s*\"[^\"]*\""), "\"privateKey\": \"[PROTECTED_KEY]\"")
    }
}
