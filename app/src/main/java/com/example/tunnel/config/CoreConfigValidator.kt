package com.example.tunnel.config

import com.example.model.TunnelProfile
import com.example.model.TunnelProtocol

enum class TransportType(val id: String) {
    TCP("tcp"),
    WEBSOCKET("ws"),
    GRPC("grpc"),
    XHTTP("xhttp")
}

enum class SecurityType(val id: String) {
    NONE("none"),
    TLS("tls"),
    REALITY("reality")
}

data class ParsedProfileConfig(
    val serverHost: String,
    val serverPort: Int,
    val sniHost: String,
    val wsHostHeader: String,
    val transport: TransportType,
    val wsOrXhttpPath: String,
    val grpcServiceName: String,
    val security: SecurityType,
    val realityPublicKey: String?,
    val realityShortId: String,
    val realityFingerprint: String,
    val alpnList: List<String>,
    val ssMethod: String?,
    val ssPassword: String?
)

object CoreConfigValidator {

    private val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    val SUPPORTED_SHADOWSOCKS_METHODS = setOf(
        "aes-128-gcm",
        "aes-256-gcm",
        "chacha20-ietf-poly1305",
        "xchacha20-ietf-poly1305",
        "2022-blake3-aes-128-gcm",
        "2022-blake3-aes-256-gcm",
        "2022-blake3-chacha20-poly1305"
    )

    fun validateAndParse(
        profile: TunnelProfile,
        allowV2RayAsVmess: Boolean = false
    ): ParsedProfileConfig {
        val host = profile.serverHost.trim()
        if (host.isEmpty()) {
            throw IllegalArgumentException("Server host cannot be empty")
        }
        if (containsControlOrWhitespace(host)) {
            throw IllegalArgumentException("Server host contains illegal whitespace or CRLF characters")
        }
        if (profile.serverPort !in 1..65535) {
            throw IllegalArgumentException("Server port must be between 1 and 65535 (was: ${profile.serverPort})")
        }

        val credential = profile.uuidOrPassword.trim()
        if (credential.contains('\r') || credential.contains('\n')) {
            throw IllegalArgumentException("Credential contains illegal CRLF characters")
        }

        var ssMethod: String? = null
        var ssPassword: String? = null

        when (profile.protocol) {
            TunnelProtocol.VLESS, TunnelProtocol.VMESS -> {
                if (!UUID_REGEX.matches(credential)) {
                    throw IllegalArgumentException("${profile.protocol.displayName} requires a valid UUID")
                }
            }
            TunnelProtocol.V2RAY -> {
                if (!allowV2RayAsVmess) {
                    throw UnsupportedOperationException("Protocol V2RAY must be routed through Xray-core as VMess")
                }
                if (!UUID_REGEX.matches(credential)) {
                    throw IllegalArgumentException("V2Ray (VMess) requires a valid UUID")
                }
            }
            TunnelProtocol.TROJAN -> {
                if (credential.isEmpty()) {
                    throw IllegalArgumentException("Trojan requires a non-empty password")
                }
            }
            TunnelProtocol.SHADOWSOCKS -> {
                if (credential.contains(":")) {
                    val parts = credential.split(":", limit = 2)
                    val method = parts[0].trim().lowercase()
                    val password = parts[1]
                    if (method !in SUPPORTED_SHADOWSOCKS_METHODS) {
                        throw IllegalArgumentException("Unsupported Shadowsocks cipher method: '$method'")
                    }
                    if (password.isBlank()) {
                        throw IllegalArgumentException("Shadowsocks requires a non-empty password")
                    }
                    ssMethod = method
                    ssPassword = password
                } else {
                    if (credential.isEmpty()) {
                        throw IllegalArgumentException("Shadowsocks requires method:password or non-empty password")
                    }
                    ssMethod = "chacha20-ietf-poly1305"
                    ssPassword = credential
                }
            }
            else -> {
                throw UnsupportedOperationException(
                    "Protocol ${profile.protocol.displayName} is not a V2Ray/sing-box core protocol"
                )
            }
        }

        val rawPayload = profile.dpiTweak.httpPayload
        val sni = profile.dpiTweak.sniHost.trim()
        if (containsControlOrWhitespace(sni)) {
            throw IllegalArgumentException("SNI host contains illegal whitespace or CRLF characters")
        }
        val effectiveSni = sni.ifEmpty { host }

        val alpnList = profile.dpiTweak.tlsAlpn
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .onEach { alpn ->
                if (alpn.contains('\r') || alpn.contains('\n')) {
                    throw IllegalArgumentException("ALPN contains illegal CRLF characters")
                }
            }

        // Security detection (strict: never substitute fallback Reality keys)
        val hasRealityParam = rawPayload.contains("pbk=", ignoreCase = true) ||
            rawPayload.contains("security=reality", ignoreCase = true) ||
            profile.name.contains("Reality", ignoreCase = true)

        val security: SecurityType
        var realityPublicKey: String? = null
        var realityShortId = ""
        val realityFingerprint = Regex("(?:fp|fingerprint)=([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
            .find(rawPayload)?.groupValues?.get(1) ?: "chrome"

        if (hasRealityParam) {
            if (profile.protocol != TunnelProtocol.VLESS && profile.protocol != TunnelProtocol.TROJAN) {
                throw IllegalArgumentException("Reality security is only supported for VLESS and Trojan profiles")
            }
            val pbkMatch = Regex("pbk=([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE).find(rawPayload)
            val extractedPbk = pbkMatch?.groupValues?.get(1)?.trim().orEmpty()
            if (extractedPbk.isEmpty()) {
                throw IllegalArgumentException("Reality security requires a non-empty pbk= (public key) parameter")
            }
            val sidMatch = Regex("sid=([A-Za-z0-9_-]*)", RegexOption.IGNORE_CASE).find(rawPayload)
            realityPublicKey = extractedPbk
            realityShortId = sidMatch?.groupValues?.get(1)?.trim().orEmpty()
            security = SecurityType.REALITY
        } else if (profile.protocol == TunnelProtocol.TROJAN ||
            profile.serverPort == 443 ||
            rawPayload.contains("security=tls", ignoreCase = true)
        ) {
            security = SecurityType.TLS
        } else {
            security = SecurityType.NONE
        }

        // Transport detection
        val transport = when {
            rawPayload.contains("type=xhttp", ignoreCase = true) ||
                rawPayload.contains("xhttp", ignoreCase = true) ||
                profile.name.contains("XHTTP", ignoreCase = true) -> TransportType.XHTTP

            rawPayload.contains("type=ws", ignoreCase = true) ||
                (profile.dpiTweak.fakeHttpRequest && (
                    rawPayload.contains("Upgrade: websocket", ignoreCase = true) ||
                    rawPayload.contains("websocket", ignoreCase = true)
                )) ||
                profile.name.contains("WS", ignoreCase = true) -> TransportType.WEBSOCKET

            rawPayload.contains("type=grpc", ignoreCase = true) ||
                rawPayload.contains("serviceName=", ignoreCase = true) ||
                profile.name.contains("gRPC", ignoreCase = true) -> TransportType.GRPC

            else -> TransportType.TCP
        }

        val extractedPath = extractPath(rawPayload)
        val extractedHostHeader = extractHttpHostHeader(rawPayload)
        val wsHostHeader = extractedHostHeader ?: effectiveSni
        if (wsHostHeader.contains('\r') || wsHostHeader.contains('\n') || wsHostHeader.any { it.isWhitespace() }) {
            throw IllegalArgumentException("WebSocket/HTTP Host header contains illegal characters")
        }

        val grpcService = Regex("(?:serviceName|service_name)=([A-Za-z0-9_./-]+)", RegexOption.IGNORE_CASE)
            .find(rawPayload)?.groupValues?.get(1)?.trim() ?: "tunnel-grpc"

        return ParsedProfileConfig(
            serverHost = host,
            serverPort = profile.serverPort,
            sniHost = effectiveSni,
            wsHostHeader = wsHostHeader,
            transport = transport,
            wsOrXhttpPath = extractedPath,
            grpcServiceName = grpcService,
            security = security,
            realityPublicKey = realityPublicKey,
            realityShortId = realityShortId,
            realityFingerprint = realityFingerprint,
            alpnList = alpnList,
            ssMethod = ssMethod,
            ssPassword = ssPassword
        )
    }

    private fun extractPath(rawPayload: String): String {
        val paramMatch = Regex("(?:^|[&?\\s])path=([^&\\s\\r\\n\\[]+)", RegexOption.IGNORE_CASE)
            .find(rawPayload)
        val fromParam = paramMatch?.groupValues?.get(1)?.trim()
        if (!fromParam.isNullOrEmpty()) {
            if (!fromParam.startsWith("/")) {
                throw IllegalArgumentException("Transport path must start with '/' (was: '$fromParam')")
            }
            return fromParam
        }

        val httpLineMatch = Regex("(?:GET|POST|HEAD|PUT)\\s+(/[^\\s\\r\\n\\[]*)\\s+HTTP/", RegexOption.IGNORE_CASE)
            .find(rawPayload.replace("[crlf]", "\r\n"))
        val fromLine = httpLineMatch?.groupValues?.get(1)?.trim()
        if (!fromLine.isNullOrEmpty()) {
            return fromLine
        }

        return "/tunnel"
    }

    private fun extractHttpHostHeader(rawPayload: String): String? {
        val normalized = rawPayload.replace("[crlf]", "\r\n").replace("[cr]", "\r").replace("[lf]", "\n")
        val match = Regex("(?im)^Host:\\s*([^\\r\\n]+)$").find(normalized)
        val raw = match?.groupValues?.get(1)?.trim() ?: return null
        if (raw.isEmpty() || raw.contains("[host]", ignoreCase = true) || raw.contains("[host_port]", ignoreCase = true)) {
            return null
        }
        return raw
    }

    private fun containsControlOrWhitespace(value: String): Boolean {
        return value.contains('\r') || value.contains('\n') || value.any { it.isWhitespace() }
    }
}
