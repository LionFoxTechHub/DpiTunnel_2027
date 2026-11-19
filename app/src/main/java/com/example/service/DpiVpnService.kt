package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.TunnelRepository
import com.example.model.*
import com.example.tunnel.SocketProtector
import com.example.tunnel.TunnelOrchestrator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.DatagramSocket
import java.net.Socket

class DpiVpnService : VpnService(), SocketProtector {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private val lifecycleMutex = Mutex()
    private var activeConnectJob: Job? = null

    private lateinit var orchestrator: TunnelOrchestrator
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun protect(socket: Socket): Boolean = super.protect(socket)
    override fun protect(socket: DatagramSocket): Boolean = super.protect(socket)
    override fun protect(socketFd: Int): Boolean = super.protect(socketFd)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        orchestrator = TunnelOrchestrator(
            socketProtector = this,
            onStateChanged = { newState ->
                _vpnState.value = newState
            },
            onNotificationUpdateNeeded = { statusText, profile ->
                val notifManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                notifManager.notify(NOTIFICATION_ID, buildNotification(statusText, profile))
            },
            appContext = applicationContext,
            workingDirPath = filesDir.absolutePath
        )

        setupNetworkMonitoring()
    }

    private fun setupNetworkMonitoring() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                orchestrator.handleNetworkAvailable()
            }

            override fun onLost(network: Network) {
                orchestrator.handleNetworkLost()
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager?.registerNetworkCallback(request, networkCallback!!)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_DISCONNECT) {
            disconnectTunnel("User disconnected via notification")
            return START_NOT_STICKY
        }

        val profileId = intent?.getStringExtra(EXTRA_PROFILE_ID)
        val profile = TunnelRepository.profiles.value.firstOrNull { it.id == profileId }
            ?: TunnelRepository.selectedProfile.value

        connectTunnel(profile)
        return START_STICKY
    }

    private fun connectTunnel(profile: TunnelProfile) {
        activeConnectJob?.cancel()
        activeConnectJob = serviceScope.launch {
            lifecycleMutex.withLock {
                startForeground(NOTIFICATION_ID, buildNotification("Validating ${profile.name}...", profile))
                addLog(
                    LogLevel.INFO,
                    "VPN_SERVICE",
                    "Starting tunnel pipeline for: ${profile.name} (${profile.protocol.displayName}, engine=${profile.preferredEngine.displayName})"
                )

                val mtu = 1400
                val enableIpv6 = false
                val dnsServers = listOf("1.1.1.1", "8.8.8.8")

                val startResult = orchestrator.startTunnel(
                    profile = profile,
                    mtu = mtu,
                    enableIpv6 = enableIpv6,
                    dnsServers = dnsServers,
                    tunEstablisher = {
                        establishTunInterface(profile, mtu, enableIpv6, dnsServers)
                    }
                )

                if (startResult.isFailure) {
                    val ex = startResult.exceptionOrNull()
                    addLog(LogLevel.ERROR, "TUNNEL", "Tunnel failed to connect: ${ex?.message}")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun establishTunInterface(
        profile: TunnelProfile,
        mtu: Int,
        enableIpv6: Boolean,
        dnsServers: List<String>
    ): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("DpiTunnel [${profile.protocol.shortName}]")
            .addAddress("10.8.0.2", 24)
            .addDnsServer(dnsServers[0])
            .addDnsServer(dnsServers[1])
            .addRoute("0.0.0.0", 0)
            .setMtu(mtu)

        if (enableIpv6) {
            builder.addAddress("fd00:10:8::2", 64)
            builder.addRoute("::", 0)
        }

        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            addLog(LogLevel.WARN, "VPN_SERVICE", "Could not set disallowed app: ${e.message}")
        }

        val pfd = builder.establish()
        if (pfd != null) {
            addLog(LogLevel.INFO, "TUN", "Android TUN established on fd=${pfd.fd} (MTU=$mtu)")
        }
        return pfd
    }

    private fun disconnectTunnel(reason: String) {
        activeConnectJob?.cancel()
        serviceScope.launch {
            lifecycleMutex.withLock {
                orchestrator.stopTunnel(reason)
                addLog(LogLevel.WARN, "VPN_SERVICE", "Tunnel terminated ($reason)")
                _vpnState.value = _vpnState.value.copy(
                    status = VpnStatus.DISCONNECTED,
                    downloadSpeedBps = 0L,
                    uploadSpeedBps = 0L,
                    errorMessage = null
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onRevoke() {
        disconnectTunnel("VPN permission revoked by system")
        super.onRevoke()
    }

    override fun onDestroy() {
        activeConnectJob?.cancel()
        networkCallback?.let {
            try { connectivityManager?.unregisterNetworkCallback(it) } catch (_: Throwable) {}
        }
        runBlocking {
            withTimeoutOrNull(2500L) {
                try {
                    orchestrator.stopTunnel("Service destroyed")
                } catch (_: Throwable) {}
            }
        }
        orchestrator.release()
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DpiTunnel Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live verified status and real throughput of DpiTunnel"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String, profile: TunnelProfile): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, DpiVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DpiTunnel: ${profile.name}")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(0, "Disconnect", disconnectPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "dpi_tunnel_notification_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_CONNECT = "com.lionfoxtech.DpiTunnel.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.lionfoxtech.DpiTunnel.ACTION_DISCONNECT"
        const val EXTRA_PROFILE_ID = "extra_profile_id"

        private val _vpnState = MutableStateFlow(VpnState())
        val vpnState: StateFlow<VpnState> = _vpnState.asStateFlow()

        fun start(context: Context, profile: TunnelProfile) {
            val intent = Intent(context, DpiVpnService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_PROFILE_ID, profile.id)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DpiVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            context.startService(intent)
        }

        fun addLog(level: LogLevel, tag: String, message: String) {
            val entry = LogEntry(level = level, tag = tag, message = message)
            val currentLogs = _vpnState.value.logs
            val updated = (currentLogs + entry).takeLast(300)
            _vpnState.value = _vpnState.value.copy(logs = updated)
        }

        fun clearLogs() {
            _vpnState.value = _vpnState.value.copy(logs = emptyList())
        }
    }
}
