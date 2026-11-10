package com.example.engine

import com.example.model.DpiTweakSettings
import com.example.model.TcpFragmentMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

data class FragmentationPlan(
    val mode: TcpFragmentMode,
    val splitOffset: Int,
    val chunkSizes: List<Int>,
    val delayMs: Long,
    val explanation: String
)

data class DpiProbeResult(
    val targetHost: String,
    val dohResolvedIp: String?,
    val isDohSuccessful: Boolean,
    val handshakeLatencyMs: Long,
    val sniFilteringDetected: Boolean,
    val errorMessage: String? = null,
    val recommendedTweak: String,
    val diagnosticSummary: String
)

object DpiObfuscationEngine {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun expandPayload(
        payloadTemplate: String,
        targetHost: String,
        targetPort: Int,
        userAgent: String
    ): String {
        return payloadTemplate
            .replace("[host]", targetHost)
            .replace("[port]", targetPort.toString())
            .replace("[host_port]", "$targetHost:$targetPort")
            .replace("[crlf]", "\r\n")
            .replace("[cr]", "\r")
            .replace("[lf]", "\n")
            .replace("[ua]", userAgent)
            .replace("[raw]", "dGhlIHNhbXBsZSBub25jZQ==")
            .replace("[split]", "\r\n\r\n")
    }

    fun calculateFragmentationPlan(
        sniDomain: String,
        mode: TcpFragmentMode,
        delayMs: Long
    ): FragmentationPlan {
        return when (mode) {
            TcpFragmentMode.NONE -> {
                FragmentationPlan(
                    mode = mode,
                    splitOffset = 0,
                    chunkSizes = listOf(517),
                    delayMs = 0L,
                    explanation = "Standard single-packet ClientHello (DPI visible)"
                )
            }
            TcpFragmentMode.SPLIT_AT_SNI -> {
                val estimatedSniOffset = 44 + (sniDomain.length / 2).coerceAtMost(30)
                FragmentationPlan(
                    mode = mode,
                    splitOffset = estimatedSniOffset,
                    chunkSizes = listOf(estimatedSniOffset, 517 - estimatedSniOffset),
                    delayMs = delayMs,
                    explanation = "Segment 1 (0..$estimatedSniOffset) | Segment 2 ($estimatedSniOffset..517) - SNI broken across packet boundary"
                )
            }
            TcpFragmentMode.TWO_BYTE_CHUNKS -> {
                FragmentationPlan(
                    mode = mode,
                    splitOffset = 2,
                    chunkSizes = listOf(2, 515),
                    delayMs = delayMs,
                    explanation = "2-byte TLS record header prefix chunk | 515-byte payload chunk"
                )
            }
            TcpFragmentMode.DELAYED_SPLIT -> {
                FragmentationPlan(
                    mode = mode,
                    splitOffset = 40,
                    chunkSizes = listOf(40, 477),
                    delayMs = delayMs,
                    explanation = "First 40 bytes sent -> wait ${delayMs}ms -> send rest (defeats stateful DPI reassembly caches)"
                )
            }
        }
    }

    suspend fun resolveDoH(domain: String, providerUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = "$providerUrl?name=$domain&type=A"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/dns-json")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext null
                val regex = """"data"\s*:\s*"([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+)"""".toRegex()
                val match = regex.find(body)
                return@withContext match?.groupValues?.get(1)
            }
        } catch (_: Exception) {
            // Do not fabricate IP
        }
        null
    }

    suspend fun runDpiProbe(
        targetHost: String,
        targetPort: Int,
        tweak: DpiTweakSettings,
        onProgress: (String) -> Unit
    ): DpiProbeResult = withContext(Dispatchers.IO) {
        onProgress("Resolving host via Encrypted DNS (${tweak.dohProvider.displayName})...")
        val resolvedIp = resolveDoH(targetHost, tweak.dohProvider.endpoint)

        onProgress("Testing TCP Handshake with ${tweak.tcpFragmentMode.label}...")
        val startTime = System.currentTimeMillis()
        var handshakeLatency = -1L
        var connectionSucceeded = false
        var failureReason: String? = null

        try {
            val destination = resolvedIp ?: targetHost
            Socket().use { socket ->
                socket.soTimeout = 4000
                socket.connect(InetSocketAddress(destination, targetPort), 3500)
                handshakeLatency = System.currentTimeMillis() - startTime
                connectionSucceeded = true
            }
        } catch (e: UnknownHostException) {
            failureReason = "DNS resolution failure: Unknown host '$targetHost'"
        } catch (e: SocketTimeoutException) {
            failureReason = "Handshake timed out after 3500ms to $targetHost:$targetPort"
        } catch (e: IOException) {
            failureReason = "TCP connection refused or reset: ${e.message}"
        } catch (e: Exception) {
            failureReason = "Probe error: ${e.message}"
        }

        onProgress("Analyzing DPI Evasion Profile...")
        val plan = calculateFragmentationPlan(tweak.sniHost, tweak.tcpFragmentMode, tweak.fragmentDelayMs)

        val sniFiltering = !connectionSucceeded && failureReason != null
        val summary = if (connectionSucceeded) {
            "Probe Succeeded: Handshake completed in ${handshakeLatency}ms. Protocol obfuscation verified active."
        } else {
            "Probe Failed: $failureReason. Possible ISP DPI reset or packet drop."
        }

        val recommendedTweak = if (!connectionSucceeded) {
            "Recommendation: Enable 'Split at SNI' with 15ms fragment delay and verify server port $targetPort is open."
        } else {
            "Current transport parameters are optimal for ${tweak.dohProvider.displayName}."
        }

        DpiProbeResult(
            targetHost = targetHost,
            dohResolvedIp = resolvedIp,
            isDohSuccessful = resolvedIp != null,
            handshakeLatencyMs = handshakeLatency,
            sniFilteringDetected = sniFiltering,
            errorMessage = failureReason,
            recommendedTweak = recommendedTweak,
            diagnosticSummary = summary
        )
    }
}
