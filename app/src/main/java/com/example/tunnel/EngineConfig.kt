package com.example.tunnel

import android.content.Context
import android.os.ParcelFileDescriptor
import com.example.model.TunnelProfile

data class EngineConfig(
    val profile: TunnelProfile,
    val tunFd: ParcelFileDescriptor,
    val mtu: Int = 1400,
    val enableIpv6: Boolean = false,
    val dnsServers: List<String> = listOf("1.1.1.1", "8.8.8.8"),
    val appPackageName: String = "com.lionfoxtech.DpiTunnel",
    val appContext: Context? = null,
    val workingDirPath: String? = null,
    val verificationSocksPort: Int = 0
)

data class HealthCheckResult(
    val isHealthy: Boolean,
    val latencyMs: Long,
    val details: String
)
