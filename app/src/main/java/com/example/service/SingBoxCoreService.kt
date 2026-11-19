package com.example.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import io.nekohasekai.libbox.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Dedicated Android service running in `android:process=":singbox"`.
 * Loads strictly `libbox.so` (never `libgojni.so`) so sing-box's Go runtime and JNI symbols
 * stay completely isolated from Xray-core.
 */
class SingBoxCoreService : Service() {

    private val setupDone = AtomicBoolean(false)
    private val serviceRunning = AtomicBoolean(false)
    private val totalUplinkBytes = AtomicLong(0L)
    private val totalDownlinkBytes = AtomicLong(0L)

    @Volatile private var commandServer: CommandServer? = null
    @Volatile private var commandClient: CommandClient? = null
    @Volatile private var verificationPort: Int = 0
    @Volatile private var lastError: String? = null
    @Volatile private var ownedTunPfd: ParcelFileDescriptor? = null
    private val tunFdDetached = AtomicBoolean(false)

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return when (code) {
                CoreWorkerIpc.TX_START -> {
                    data.enforceInterface(CoreWorkerIpc.DESCRIPTOR_WORKER)
                    val configJson = data.readString() ?: ""
                    val tunPfd = ParcelFileDescriptor.CREATOR.createFromParcel(data)
                    val workingDir = data.readString() ?: filesDir.absolutePath
                    val socksPort = data.readInt()
                    val protectBinder = data.readStrongBinder()
                    val result = handleStart(configJson, tunPfd, workingDir, socksPort, protectBinder)
                    reply?.writeNoException()
                    reply?.writeInt(if (result.success) 1 else 0)
                    reply?.writeString(result.message)
                    reply?.writeString(result.coreVersion)
                    true
                }
                CoreWorkerIpc.TX_VERIFY -> {
                    data.enforceInterface(CoreWorkerIpc.DESCRIPTOR_WORKER)
                    val verify = handleVerify()
                    reply?.writeNoException()
                    reply?.writeInt(if (verify.healthy) 1 else 0)
                    reply?.writeLong(verify.latencyMs)
                    reply?.writeString(verify.details)
                    true
                }
                CoreWorkerIpc.TX_STATS -> {
                    data.enforceInterface(CoreWorkerIpc.DESCRIPTOR_WORKER)
                    reply?.writeNoException()
                    reply?.writeInt(if (serviceRunning.get()) 1 else 0)
                    reply?.writeLong(totalUplinkBytes.get())
                    reply?.writeLong(totalDownlinkBytes.get())
                    reply?.writeString(lastError)
                    true
                }
                CoreWorkerIpc.TX_STOP -> {
                    data.enforceInterface(CoreWorkerIpc.DESCRIPTOR_WORKER)
                    handleStop()
                    reply?.writeNoException()
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun handleStart(
        configJson: String,
        tunPfd: ParcelFileDescriptor,
        workingDir: String,
        socksPort: Int,
        protectBinder: IBinder
    ): CoreWorkerIpc.StartResult {
        handleStop()
        ownedTunPfd = tunPfd
        tunFdDetached.set(false)
        verificationPort = socksPort
        lastError = null
        totalUplinkBytes.set(0L)
        totalDownlinkBytes.set(0L)

        return try {
            val baseFolder = File(workingDir, "singbox").apply { mkdirs() }
            val tempFolder = File(cacheDir, "singbox_tmp").apply { mkdirs() }

            if (setupDone.compareAndSet(false, true)) {
                val setupOptions = SetupOptions().apply {
                    basePath = baseFolder.absolutePath
                    workingPath = baseFolder.absolutePath
                    tempPath = tempFolder.absolutePath
                    fixAndroidStack = true
                    debug = false
                }
                Libbox.setup(setupOptions)
            }

            // Genuine upstream configuration check via libbox
            Libbox.checkConfig(configJson)

            val platformInterface = object : PlatformInterface {
                override fun autoDetectInterfaceControl(fd: Int) {
                    val protected = CoreWorkerIpc.protectNativeFd(protectBinder, fd)
                    if (!protected) {
                        throw SecurityException("Outbound socket protection failed for fd=$fd (fail-closed)")
                    }
                }

                override fun clearDNSCache() {}

                override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {}

                override fun findConnectionOwner(
                    ipProtocol: Int,
                    srcIp: String,
                    srcPort: Int,
                    destIp: String,
                    destPort: Int
                ): ConnectionOwner = ConnectionOwner()

                override fun getInterfaces(): NetworkInterfaceIterator = object : NetworkInterfaceIterator {
                    override fun hasNext(): Boolean = false
                    override fun next(): NetworkInterface = NetworkInterface()
                }

                override fun includeAllNetworks(): Boolean = false

                override fun localDNSTransport(): LocalDNSTransport? = null

                override fun openTun(options: TunOptions): Int {
                    val pfd = ownedTunPfd
                        ?: throw IllegalStateException("No TUN ParcelFileDescriptor available for sing-box")
                    if (!tunFdDetached.compareAndSet(false, true)) {
                        throw IllegalStateException("TUN FD already handed off to sing-box")
                    }
                    ownedTunPfd = null
                    return pfd.detachFd()
                }

                override fun readWIFIState(): WIFIState = WIFIState("", "")

                override fun sendNotification(notification: Notification) {}

                override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {}

                override fun systemCertificates(): StringIterator = object : StringIterator {
                    override fun hasNext(): Boolean = false
                    override fun len(): Int = 0
                    override fun next(): String = ""
                }

                override fun underNetworkExtension(): Boolean = false

                override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

                override fun useProcFS(): Boolean = false
            }

            val serverHandler = object : CommandServerHandler {
                override fun serviceReload() {}
                override fun serviceStop() {
                    serviceRunning.set(false)
                }
                override fun getSystemProxyStatus(): SystemProxyStatus? = null
                override fun setSystemProxyEnabled(isEnabled: Boolean) {}
                override fun writeDebugMessage(message: String) {}
            }

            val server = Libbox.newCommandServer(serverHandler, platformInterface)
            commandServer = server
            server.start()
            server.startOrReloadService(configJson, OverrideOptions())
            if (!tunFdDetached.get()) {
                val err = "sing-box did not claim Android TUN FD via PlatformInterface.openTun()"
                lastError = err
                handleStop()
                return CoreWorkerIpc.StartResult(false, err, "")
            }
            serviceRunning.set(true)

            startCommandClientMonitor()

            val coreVer = try { Libbox.version() } catch (_: Throwable) { "libbox" }
            CoreWorkerIpc.StartResult(
                success = true,
                message = "sing-box core started (tun detached=true)",
                coreVersion = coreVer
            )
        } catch (t: Throwable) {
            val msg = t.message ?: t::class.java.simpleName
            lastError = msg
            handleStop()
            CoreWorkerIpc.StartResult(false, "sing-box failed: $msg", "")
        }
    }

    private fun startCommandClientMonitor() {
        try {
            val clientHandler = object : CommandClientHandler {
                override fun clearLogs() {}
                override fun connected() {}
                override fun disconnected(message: String?) {
                    if (!message.isNullOrBlank()) {
                        lastError = message
                    }
                }
                override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) {}
                override fun setDefaultLogLevel(level: Int) {}
                override fun updateClashMode(newMode: String?) {}
                override fun writeConnectionEvents(events: ConnectionEvents?) {}
                override fun writeGroups(groups: OutboundGroupIterator?) {}
                override fun writeLogs(messageList: LogIterator?) {}
                override fun writeServiceStatus(message: ServiceStatusMessage?) {}
                override fun writeStatus(message: StatusMessage?) {
                    if (message != null) {
                        totalUplinkBytes.set(message.uplinkTotal)
                        totalDownlinkBytes.set(message.downlinkTotal)
                    }
                }
            }
            val options = CommandClientOptions().apply {
                statusInterval = 1_000_000_000L
            }
            val client = Libbox.newCommandClient(clientHandler, options)
            commandClient = client
            client.connect()
        } catch (_: Throwable) {
            // CommandClient telemetry subscription is non-fatal if CommandServer started cleanly
        }
    }

    private fun handleVerify(): CoreWorkerIpc.VerifyResult {
        if (!serviceRunning.get() || commandServer == null) {
            return CoreWorkerIpc.VerifyResult(false, -1L, lastError ?: "sing-box service is not running")
        }

        val socksPort = verificationPort
        if (socksPort !in 1..65535) {
            return CoreWorkerIpc.VerifyResult(
                false,
                -1L,
                "Verification SOCKS5 port not configured; cannot verify tunnel data path"
            )
        }

        val startMs = System.currentTimeMillis()
        val httpClient = Libbox.newHTTPClient()
        return try {
            httpClient.trySocks5(socksPort)
            val req = httpClient.newRequest().apply {
                setMethod("HEAD")
                setURL("https://cp.cloudflare.com/generate_204")
            }
            req.execute()
            val latency = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            CoreWorkerIpc.VerifyResult(
                healthy = true,
                latencyMs = latency,
                details = "sing-box tunneled HTTP probe succeeded via socks5://127.0.0.1:$socksPort (${latency}ms)"
            )
        } catch (t: Throwable) {
            CoreWorkerIpc.VerifyResult(
                healthy = false,
                latencyMs = -1L,
                details = "sing-box tunneled probe failed: ${t.message}"
            )
        } finally {
            try { httpClient.close() } catch (_: Throwable) {}
        }
    }

    private fun handleStop() {
        serviceRunning.set(false)
        try { commandClient?.disconnect() } catch (_: Throwable) {}
        commandClient = null
        try { commandServer?.closeService() } catch (_: Throwable) {}
        try { commandServer?.close() } catch (_: Throwable) {}
        commandServer = null

        if (!tunFdDetached.get()) {
            try { ownedTunPfd?.close() } catch (_: Throwable) {}
        }
        ownedTunPfd = null
    }

    override fun onDestroy() {
        handleStop()
        super.onDestroy()
    }
}
