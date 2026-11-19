package com.example.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.util.Base64
import libXray.DialerController
import libXray.LibXray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocketFactory

/**
 * Dedicated Android service running in `android:process=":xray"`.
 * Loads strictly `libgojni.so` (never `libbox.so`) so Xray-core's Go runtime and JNI symbols
 * remain isolated from sing-box.
 */
class XrayCoreService : Service() {

    private val xrayRunning = AtomicBoolean(false)
    @Volatile private var ownedTunPfd: ParcelFileDescriptor? = null
    @Volatile private var verificationPort: Int = 0
    @Volatile private var lastError: String? = null

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
                    val running = xrayRunning.get() && try { LibXray.getXrayState() } catch (_: Throwable) { false }
                    reply?.writeNoException()
                    reply?.writeInt(if (running) 1 else 0)
                    reply?.writeLong(0L)
                    reply?.writeLong(0L)
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
        verificationPort = socksPort
        lastError = null

        return try {
            val datDir = File(workingDir, "xray_dat").apply { mkdirs() }.absolutePath

            // Register outbound socket protector (fail closed if protection fails)
            LibXray.registerDialerController(DialerController { fdLong ->
                CoreWorkerIpc.protectNativeFd(protectBinder, fdLong.toInt())
            })

            // Build Base64-encoded RunXrayFromJSONRequest
            val encodedRequest = LibXray.newXrayRunFromJSONRequest(datDir, configJson)

            // Validate configuration inside Xray-core before setting TUN FD
            val testResp = decodeCallResponse(LibXray.testXray(encodedRequest))
            if (!testResp.success) {
                val err = testResp.error.ifBlank { "Xray testXray validation failed" }
                lastError = err
                handleStop()
                return CoreWorkerIpc.StartResult(false, err, "")
            }

            // Pass raw FD without detaching: in this libgojni.so build AndroidTun.Close() and
            // gvisor fdbased.endpoint.Close() are no-ops, so XrayCoreService retains close ownership
            // and closes ownedTunPfd deterministically in handleStop() after LibXray.stopXray().
            val rawTunFd = ownedTunPfd?.fd
                ?: throw IllegalStateException("TUN ParcelFileDescriptor missing")
            LibXray.setTunFd(rawTunFd)

            // Launch Xray-core
            val runResp = decodeCallResponse(LibXray.runXrayFromJSON(encodedRequest))
            if (!runResp.success || !LibXray.getXrayState()) {
                val err = runResp.error.ifBlank { "Xray failed to enter running state" }
                lastError = err
                handleStop()
                return CoreWorkerIpc.StartResult(false, err, "")
            }

            xrayRunning.set(true)
            val version = try { LibXray.xrayVersion() } catch (_: Throwable) { "xray-core" }
            CoreWorkerIpc.StartResult(
                success = true,
                message = "Xray-core started on TUN fd=$rawTunFd",
                coreVersion = version
            )
        } catch (t: Throwable) {
            val msg = t.message ?: t::class.java.simpleName
            lastError = msg
            handleStop()
            CoreWorkerIpc.StartResult(false, "Xray failed: $msg", "")
        }
    }

    private fun handleVerify(): CoreWorkerIpc.VerifyResult {
        val stateActive = try { LibXray.getXrayState() } catch (_: Throwable) { false }
        if (!xrayRunning.get() || !stateActive) {
            return CoreWorkerIpc.VerifyResult(false, -1L, lastError ?: "Xray-core is not running")
        }

        val socksPort = verificationPort
        if (socksPort !in 1..65535) {
            return CoreWorkerIpc.VerifyResult(false, -1L, "Verification SOCKS5 port missing")
        }

        val startMs = System.currentTimeMillis()
        return try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
            Socket(proxy).use { tunneledSocket ->
                tunneledSocket.soTimeout = 5000
                tunneledSocket.connect(InetSocketAddress("cp.cloudflare.com", 443), 5000)
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(tunneledSocket, "cp.cloudflare.com", 443, true)
                ssl.use { tls ->
                    val out = tls.getOutputStream()
                    out.write(
                        ("HEAD /generate_204 HTTP/1.1\r\n" +
                            "Host: cp.cloudflare.com\r\n" +
                            "Connection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII)
                    )
                    out.flush()
                    val firstByte = tls.getInputStream().read()
                    if (firstByte < 0) {
                        throw IllegalStateException("Empty response from tunneled HTTPS probe")
                    }
                }
            }
            val latency = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            CoreWorkerIpc.VerifyResult(
                healthy = true,
                latencyMs = latency,
                details = "Xray tunneled HTTPS probe succeeded via socks5://127.0.0.1:$socksPort (${latency}ms)"
            )
        } catch (t: Throwable) {
            CoreWorkerIpc.VerifyResult(
                healthy = false,
                latencyMs = -1L,
                details = "Xray tunneled probe failed: ${t.message}"
            )
        }
    }

    private fun handleStop() {
        xrayRunning.set(false)
        try {
            if (LibXray.getXrayState()) {
                LibXray.stopXray()
            }
        } catch (_: Throwable) {}
        try {
            LibXray.setTunFd(-1)
        } catch (_: Throwable) {}

        try {
            ownedTunPfd?.close()
        } catch (_: Throwable) {}
        ownedTunPfd = null
    }

    override fun onDestroy() {
        handleStop()
        super.onDestroy()
    }

    companion object {
        data class CallResponse(val success: Boolean, val data: String, val error: String)

        fun decodeCallResponse(encodedBase64: String): CallResponse {
            if (encodedBase64.isBlank()) {
                return CallResponse(false, "", "Empty response from LibXray")
            }
            val trimmed = encodedBase64.trim()
            val jsonStr = if (trimmed.startsWith("{")) {
                trimmed
            } else {
                val decoded = Base64.decode(trimmed, Base64.DEFAULT)
                String(decoded, StandardCharsets.UTF_8)
            }
            val obj = JSONObject(jsonStr)
            return CallResponse(
                success = obj.optBoolean("success", false),
                data = obj.optString("data", ""),
                error = obj.optString("error", "")
            )
        }
    }
}
