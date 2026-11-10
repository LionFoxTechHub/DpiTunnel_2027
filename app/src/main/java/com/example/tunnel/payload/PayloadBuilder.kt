package com.example.tunnel.payload

import java.nio.charset.StandardCharsets

/**
 * Payload engine providing full functional compatibility with HTTP Tweak / HTTP Injector payloads.
 * Evaluates dynamic tokens, custom HTTP methods, headers, and bug hosts.
 */
object PayloadBuilder {

    const val DEFAULT_CONNECT_PAYLOAD =
        "CONNECT [host_port] HTTP/1.1[crlf]Host: [host][crlf]X-Forwarded-For: [host][crlf]User-Agent: [ua][crlf]Connection: Upgrade[crlf][crlf]"

    const val WEBSOCKET_PAYLOAD =
        "GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf]Sec-WebSocket-Key: [raw][crlf]Sec-WebSocket-Version: 13[crlf][crlf]"

    const val DIRECT_FRONTING_PAYLOAD =
        "POST / HTTP/1.1[crlf]Host: [host][crlf]X-Online-Host: [host][crlf]User-Agent: [ua][crlf]Connection: Keep-Alive[crlf][crlf]"

    const val SPLIT_PAYLOAD =
        "CONNECT [host_port] [protocol][crlf]Host: [host][crlf][split]GET / [protocol][crlf]Host: [host][crlf][crlf]"

    data class ValidationResult(
        val isValid: Boolean,
        val issues: List<String> = emptyList()
    )

    fun validatePayload(payload: String): ValidationResult {
        val issues = mutableListOf<String>()
        if (payload.isBlank()) {
            issues.add("Payload cannot be empty")
            return ValidationResult(false, issues)
        }

        // Check for unbalanced brackets
        var openCount = 0
        for (char in payload) {
            if (char == '[') openCount++
            if (char == ']') openCount--
            if (openCount < 0) {
                issues.add("Unbalanced brackets: unexpected closing ']'")
                break
            }
        }
        if (openCount > 0) {
            issues.add("Unbalanced brackets: unclosed '[' token")
        }

        return ValidationResult(issues.isEmpty(), issues)
    }

    /**
     * Builds and evaluates the HTTP payload string, substituting all variables.
     */
    fun buildPayload(
        template: String,
        targetHost: String,
        targetPort: Int,
        bugHost: String? = null,
        userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        protocol: String = "HTTP/1.1",
        proxyUsername: String? = null,
        proxyPassword: String? = null
    ): String {
        val effectiveHost = if (!bugHost.isNullOrBlank()) bugHost.trim() else targetHost.trim()
        val effectivePort = targetPort.toString()
        val effectiveHostPort = "$effectiveHost:$effectivePort"

        // Generate dynamic 16-byte base64 nonce for Sec-WebSocket-Key
        val nonceBytes = ByteArray(16) { (1..127).random().toByte() }
        val rawNonce = encodeBase64(nonceBytes)

        // Proxy Auth token
        val authHeader = if (!proxyUsername.isNullOrEmpty()) {
            val creds = "$proxyUsername:${proxyPassword ?: ""}"
            "Basic " + encodeBase64(creds.toByteArray(StandardCharsets.UTF_8))
        } else {
            ""
        }

        return template
            .replace("[host_port]", effectiveHostPort)
            .replace("[host]", effectiveHost)
            .replace("[port]", effectivePort)
            .replace("[target_host]", targetHost)
            .replace("[target_port]", targetPort.toString())
            .replace("[target_host_port]", "$targetHost:$targetPort")
            .replace("[protocol]", protocol)
            .replace("[ua]", userAgent)
            .replace("[raw]", rawNonce)
            .replace("[real_raw]", rawNonce)
            .replace("[netData]", "CONNECT $effectiveHostPort $protocol")
            .replace("[auth]", authHeader)
            .replace("[crlf]", "\r\n")
            .replace("[cr]", "\r")
            .replace("[lf]", "\n")
            .replace("[split]", "\r\n\r\n")
            .replace("[delay_split]", "\r\n\r\n")
    }

    private fun encodeBase64(bytes: ByteArray): String {
        return try {
            java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes)
        } catch (_: Throwable) {
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }
    }

    /**
     * Splits payload into segments if [split] or [delay_split] was used.
     */
    fun getPayloadSegments(evaluatedPayload: String): List<ByteArray> {
        val delimiter = "\r\n\r\n"
        if (evaluatedPayload.contains(delimiter)) {
            val parts = evaluatedPayload.split(delimiter)
            val segments = mutableListOf<ByteArray>()
            for (i in parts.indices) {
                val part = parts[i]
                if (part.isNotEmpty()) {
                    val suffix = if (i < parts.size - 1) delimiter else ""
                    segments.add((part + suffix).toByteArray(StandardCharsets.UTF_8))
                }
            }
            return if (segments.isNotEmpty()) segments else listOf(evaluatedPayload.toByteArray(StandardCharsets.UTF_8))
        }
        return listOf(evaluatedPayload.toByteArray(StandardCharsets.UTF_8))
    }
}
