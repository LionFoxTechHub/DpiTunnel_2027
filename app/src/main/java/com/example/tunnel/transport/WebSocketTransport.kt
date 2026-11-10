package com.example.tunnel.transport

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Transport layer implementing RFC 6455 client WebSocket framing and handshake negotiation.
 * Enables streaming raw byte tunnels (e.g. SSH over WebSocket or proxy data over WebSocket).
 */
object WebSocketTransport {

    private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    data class WebSocketHandshakeResult(
        val isSuccessful: Boolean,
        val statusCode: Int,
        val responseHeaders: Map<String, String>,
        val errorMessage: String? = null
    )

    /**
     * Executes client-side WebSocket upgrade handshake over an existing connected socket.
     */
    fun performHandshake(
        socket: Socket,
        path: String = "/",
        hostHeader: String,
        customHeaders: Map<String, String> = emptyMap()
    ): WebSocketHandshakeResult {
        val outStream = socket.getOutputStream()
        val inStream = socket.getInputStream()

        // Generate 16-byte random key
        val keyBytes = ByteArray(16)
        SecureRandom().nextBytes(keyBytes)
        val clientKey = encodeBase64(keyBytes)

        val request = StringBuilder()
        request.append("GET $path HTTP/1.1\r\n")
        request.append("Host: $hostHeader\r\n")
        request.append("Upgrade: websocket\r\n")
        request.append("Connection: Upgrade\r\n")
        request.append("Sec-WebSocket-Key: $clientKey\r\n")
        request.append("Sec-WebSocket-Version: 13\r\n")

        customHeaders.forEach { (k, v) ->
            request.append("$k: $v\r\n")
        }
        request.append("\r\n")

        outStream.write(request.toString().toByteArray(StandardCharsets.UTF_8))
        outStream.flush()

        // Read response line & headers
        val responseText = readHttpHeader(inStream)
        val lines = responseText.split("\r\n")
        if (lines.isEmpty() || !lines[0].startsWith("HTTP/")) {
            return WebSocketHandshakeResult(
                isSuccessful = false,
                statusCode = 0,
                responseHeaders = emptyMap(),
                errorMessage = "Invalid HTTP response from server: $responseText"
            )
        }

        val statusParts = lines[0].split(" ")
        val statusCode = statusParts.getOrNull(1)?.toIntOrNull() ?: 0

        val headers = mutableMapOf<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }

        if (statusCode != 101) {
            return WebSocketHandshakeResult(
                isSuccessful = false,
                statusCode = statusCode,
                responseHeaders = headers,
                errorMessage = "WebSocket upgrade rejected with HTTP status $statusCode (${lines[0]})"
            )
        }

        // Verify Sec-WebSocket-Accept
        val acceptHeader = headers["sec-websocket-accept"]
        val expectedAccept = generateExpectedAccept(clientKey)
        if (acceptHeader != null && acceptHeader != expectedAccept) {
            return WebSocketHandshakeResult(
                isSuccessful = false,
                statusCode = 101,
                responseHeaders = headers,
                errorMessage = "Sec-WebSocket-Accept mismatch"
            )
        }

        return WebSocketHandshakeResult(
            isSuccessful = true,
            statusCode = 101,
            responseHeaders = headers
        )
    }

    /**
     * Frames binary data into RFC 6455 client frame (masked, binary opcode 0x02).
     */
    fun encodeBinaryFrame(payload: ByteArray, offset: Int = 0, length: Int = payload.size): ByteArray {
        val out = ByteArrayOutputStream()
        // FIN + opcode 0x02 (binary)
        out.write(0x82)

        // Mask bit set (0x80)
        when {
            length <= 125 -> {
                out.write(0x80 or length)
            }
            length <= 65535 -> {
                out.write(0x80 or 126)
                out.write((length shr 8) and 0xFF)
                out.write(length and 0xFF)
            }
            else -> {
                out.write(0x80 or 127)
                for (i in 7 downTo 0) {
                    out.write(((length.toLong() shr (i * 8)) and 0xFF).toInt())
                }
            }
        }

        // 4-byte random masking key
        val mask = ByteArray(4)
        SecureRandom().nextBytes(mask)
        out.write(mask)

        // Masked payload
        val masked = ByteArray(length)
        for (i in 0 until length) {
            masked[i] = (payload[offset + i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        out.write(masked)

        return out.toByteArray()
    }

    /**
     * Reads a single incoming WebSocket frame and extracts payload bytes.
     */
    fun decodeFrame(inStream: InputStream): ByteArray? {
        val b1 = inStream.read()
        if (b1 < 0) return null

        val b2 = inStream.read()
        if (b2 < 0) return null

        val isMasked = (b2 and 0x80) != 0
        var payloadLength = (b2 and 0x7F).toLong()

        if (payloadLength == 126L) {
            val high = inStream.read()
            val low = inStream.read()
            if (high < 0 || low < 0) return null
            payloadLength = ((high shl 8) or low).toLong()
        } else if (payloadLength == 127L) {
            var len = 0L
            for (i in 0 until 8) {
                val b = inStream.read()
                if (b < 0) return null
                len = (len shl 8) or b.toLong()
            }
            payloadLength = len
        }

        val maskKey = ByteArray(4)
        if (isMasked) {
            var read = 0
            while (read < 4) {
                val r = inStream.read(maskKey, read, 4 - read)
                if (r < 0) return null
                read += r
            }
        }

        if (payloadLength > 10_000_000L) {
            throw IOException("WebSocket frame exceeds 10MB limit")
        }

        val payload = ByteArray(payloadLength.toInt())
        var totalRead = 0
        while (totalRead < payloadLength.toInt()) {
            val r = inStream.read(payload, totalRead, payloadLength.toInt() - totalRead)
            if (r < 0) return null
            totalRead += r
        }

        if (isMasked) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
        }

        return payload
    }

    private fun readHttpHeader(inStream: InputStream): String {
        val out = ByteArrayOutputStream()
        var matched = 0
        val target = "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)

        while (true) {
            val b = inStream.read()
            if (b < 0) break
            out.write(b)
            if (b == target[matched].toInt()) {
                matched++
                if (matched == target.size) break
            } else {
                matched = if (b == target[0].toInt()) 1 else 0
            }
        }
        return out.toString(StandardCharsets.UTF_8.name())
    }

    private fun generateExpectedAccept(clientKey: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val hash = md.digest((clientKey + WS_GUID).toByteArray(StandardCharsets.US_ASCII))
        return encodeBase64(hash)
    }

    private fun encodeBase64(bytes: ByteArray): String {
        return try {
            java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes)
        } catch (_: Throwable) {
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }
    }
}
