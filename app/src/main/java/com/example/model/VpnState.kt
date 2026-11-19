package com.example.model

enum class VpnStatus(val displayLabel: String) {
    CONFIG_VALIDATION("Validating Config"),
    CORE_INITIALIZING("Initializing Core"),
    CORE_RUNNING("Core Running"),
    TUN_ESTABLISHING("Establishing TUN"),
    CONNECTIVITY_VERIFYING("Verifying Tunnel"),
    CONNECTED("Connected"),
    DISCONNECTING("Disconnecting"),
    DISCONNECTED("Disconnected"),
    ERROR("Error")
}

data class VpnState(
    val status: VpnStatus = VpnStatus.DISCONNECTED,
    val activeProfile: TunnelProfile? = null,
    val activeEngineName: String? = null,
    val connectedDurationSeconds: Long = 0L,
    val downloadSpeedBps: Long = 0L,
    val uploadSpeedBps: Long = 0L,
    val totalDownloadBytes: Long = 0L,
    val totalUploadBytes: Long = 0L,
    val currentVirtualIp: String = "10.8.0.2",
    val remoteServerIp: String = "",
    val activeObfuscationSummary: String = "Inactive",
    val errorMessage: String? = null,
    val logs: List<LogEntry> = emptyList()
) {
    val isConnected: Boolean
        get() = status == VpnStatus.CONNECTED

    val isTransitioning: Boolean
        get() = status == VpnStatus.CONFIG_VALIDATION ||
            status == VpnStatus.CORE_INITIALIZING ||
            status == VpnStatus.CORE_RUNNING ||
            status == VpnStatus.TUN_ESTABLISHING ||
            status == VpnStatus.CONNECTIVITY_VERIFYING ||
            status == VpnStatus.DISCONNECTING

    val formattedDuration: String
        get() {
            val hrs = connectedDurationSeconds / 3600
            val mins = (connectedDurationSeconds % 3600) / 60
            val secs = connectedDurationSeconds % 60
            return if (hrs > 0) {
                String.format("%02d:%02d:%02d", hrs, mins, secs)
            } else {
                String.format("%02d:%02d", mins, secs)
            }
        }

    val formattedDownloadSpeed: String
        get() = formatSpeed(downloadSpeedBps)

    val formattedUploadSpeed: String
        get() = formatSpeed(uploadSpeedBps)

    val formattedTotalDownload: String
        get() = formatBytes(totalDownloadBytes)

    val formattedTotalUpload: String
        get() = formatBytes(totalUploadBytes)

    companion object {
        fun formatSpeed(bytesPerSec: Long): String {
            return when {
                bytesPerSec >= 1_048_576 -> String.format("%.2f MB/s", bytesPerSec / 1_048_576.0)
                bytesPerSec >= 1024 -> String.format("%.1f KB/s", bytesPerSec / 1024.0)
                else -> "$bytesPerSec B/s"
            }
        }

        fun formatBytes(bytes: Long): String {
            return when {
                bytes >= 1_073_741_824 -> String.format("%.2f GB", bytes / 1_073_741_824.0)
                bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
                bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
                else -> "$bytes B"
            }
        }
    }
}
