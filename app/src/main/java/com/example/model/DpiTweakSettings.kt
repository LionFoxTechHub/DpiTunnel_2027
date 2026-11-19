package com.example.model

import com.example.tunnel.payload.PayloadBuilder

enum class TcpFragmentMode(val label: String, val description: String) {
    NONE("Disabled", "Standard packet transmission without fragmentation"),
    SPLIT_AT_SNI("Split at SNI", "Fragments ClientHello packet at the SNI domain boundary"),
    TWO_BYTE_CHUNKS("2-Byte Header Chunk", "Sends first 2 bytes separately to break DPI pattern matching"),
    DELAYED_SPLIT("Delayed Fragment (15ms)", "Splits handshake with micro-delay to bypass DPI reassembly caches")
}

enum class DohProvider(val displayName: String, val endpoint: String) {
    CLOUDFLARE("Cloudflare DoH", "https://cloudflare-dns.com/dns-query"),
    GOOGLE("Google Public DNS", "https://dns.google/resolve"),
    ADGUARD("AdGuard DNS", "https://dns.adguard-dns.com/dns-query"),
    QUAD9("Quad9 DNS", "https://dns.quad9.net/dns-query")
}

data class DpiTweakSettings(
    val sniHost: String = "cloudflare.com",
    val tcpFragmentMode: TcpFragmentMode = TcpFragmentMode.SPLIT_AT_SNI,
    val fragmentDelayMs: Long = 15L,
    val fakeHttpRequest: Boolean = true,
    val httpPayload: String = DEFAULT_PAYLOAD,
    val dohProvider: DohProvider = DohProvider.CLOUDFLARE,
    val tlsAlpn: String = "h2,http/1.1",
    val badChecksumEvasion: Boolean = true,
    val quicJunkPadding: Boolean = true,
    val customUserAgent: String = "Mozilla/5.0 (Android; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0"
) {
    companion object {
        const val DEFAULT_PAYLOAD = "CONNECT [host_port] HTTP/1.1[crlf]Host: [host][crlf]X-Forwarded-For: [host][crlf]User-Agent: [ua][crlf]Connection: Upgrade[crlf][crlf]"
        const val WEBSOCKET_PAYLOAD = "GET /tunnel HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf]Sec-WebSocket-Key: [raw][crlf][crlf]"
        const val DIRECT_FRONTING_PAYLOAD = "POST / HTTP/1.1[crlf]Host: [host][crlf]X-Online-Host: [host][crlf]Connection: Keep-Alive[crlf][crlf]"
    }

    /**
     * Validates configuration settings to prevent malformed or unclosed tokens
     * in the payload, and prevents CRLF header injection.
     */
    fun validate(): PayloadBuilder.ValidationResult {
        val issues = mutableListOf<String>()

        if (fakeHttpRequest) {
            val payloadValidation = PayloadBuilder.validatePayload(httpPayload)
            issues.addAll(payloadValidation.issues)
        }

        if (sniHost.contains('\r') || sniHost.contains('\n')) {
            issues.add("SNI host cannot contain illegal CRLF characters")
        }
        if (sniHost.any { it.isWhitespace() }) {
            issues.add("SNI host cannot contain whitespace")
        }
        if (customUserAgent.contains('\r') || customUserAgent.contains('\n')) {
            issues.add("Custom User-Agent cannot contain illegal CRLF characters")
        }
        if (fragmentDelayMs < 0) {
            issues.add("Fragment delay must be non-negative")
        }

        return PayloadBuilder.ValidationResult(issues.isEmpty(), issues)
    }

    /**
     * Sanitizes and validates a target host and port combination before processing.
     * Ensures target host is non-empty, contains no whitespace or CRLF characters,
     * and target port is in the valid port range 1..65535.
     */
    fun sanitizeTarget(rawHost: String, rawPort: Int): Pair<String, Int> {
        val trimmed = rawHost.trim()
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("Target host cannot be empty")
        }
        if (trimmed.contains('\r') || trimmed.contains('\n')) {
            throw IllegalArgumentException("Target host contains illegal CRLF characters")
        }
        if (trimmed.any { it.isWhitespace() }) {
            throw IllegalArgumentException("Target host cannot contain whitespace")
        }
        if (rawPort !in 1..65535) {
            throw IllegalArgumentException("Target port must be between 1 and 65535 (was: $rawPort)")
        }
        return Pair(trimmed, rawPort)
    }
}
