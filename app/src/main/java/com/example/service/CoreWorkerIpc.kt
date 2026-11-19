package com.example.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import com.example.tunnel.SocketProtector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/**
 * Synchronous Binder IPC protocol bridging the main VpnService process (`DpiVpnService`)
 * with process-isolated native core workers (`:singbox` and `:xray`).
 *
 * Isolating libbox.so (:singbox) and libgojni.so (:xray) into dedicated Linux processes prevents
 * JNI symbol collisions (`Java_go_Seq_*`) and RefTracker state corruption between the two Go runtimes,
 * while preserving genuine Android `VpnService.protect(fd)` and TUN FD ownership across Binder.
 */
object CoreWorkerIpc {
    const val DESCRIPTOR_WORKER = "com.example.service.ICoreWorker"
    const val DESCRIPTOR_PROTECTOR = "com.example.service.ISocketProtector"

    const val TX_START = IBinder.FIRST_CALL_TRANSACTION + 1
    const val TX_STOP = IBinder.FIRST_CALL_TRANSACTION + 2
    const val TX_VERIFY = IBinder.FIRST_CALL_TRANSACTION + 3
    const val TX_STATS = IBinder.FIRST_CALL_TRANSACTION + 4

    const val TX_PROTECT_FD = IBinder.FIRST_CALL_TRANSACTION + 10

    data class StartResult(
        val success: Boolean,
        val message: String,
        val coreVersion: String
    )

    data class VerifyResult(
        val healthy: Boolean,
        val latencyMs: Long,
        val details: String
    )

    data class StatsResult(
        val isRunning: Boolean,
        val txBytes: Long,
        val rxBytes: Long,
        val error: String?
    )

    /**
     * Hosted in the `DpiVpnService` process. Receives a duplicated socket ParcelFileDescriptor
     * from a worker process, invokes `VpnService.protect(fd)` on the underlying kernel socket,
     * and closes the local dup immediately.
     */
    class SocketProtectorBinder(
        private val protector: SocketProtector
    ) : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == TX_PROTECT_FD) {
                data.enforceInterface(DESCRIPTOR_PROTECTOR)
                val pfd = ParcelFileDescriptor.CREATOR.createFromParcel(data)
                val ok = try {
                    protector.protect(pfd.fd)
                } finally {
                    try {
                        pfd.close()
                    } catch (_: Throwable) {}
                }
                reply?.writeNoException()
                reply?.writeInt(if (ok) 1 else 0)
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    /**
     * Used inside `:singbox` and `:xray` worker processes to protect outbound sockets via `DpiVpnService`.
     * Fails closed (`false`) if Binder call fails or `VpnService.protect` denies protection.
     */
    fun protectNativeFd(protectBinder: IBinder, fd: Int): Boolean {
        if (fd < 0) return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        var pfd: ParcelFileDescriptor? = null
        return try {
            pfd = ParcelFileDescriptor.fromFd(fd)
            data.writeInterfaceToken(DESCRIPTOR_PROTECTOR)
            pfd.writeToParcel(data, 0)
            val transacted = protectBinder.transact(TX_PROTECT_FD, data, reply, 0)
            if (!transacted) return false
            reply.readException()
            reply.readInt() == 1
        } catch (_: Throwable) {
            false
        } finally {
            try { pfd?.close() } catch (_: Throwable) {}
            data.recycle()
            reply.recycle()
        }
    }

    class WorkerConnection(
        private val context: Context,
        private val binder: IBinder,
        private val connection: ServiceConnection
    ) {
        @Volatile private var deathRecipient: IBinder.DeathRecipient? = null

        fun setOnProcessDiedListener(onDied: () -> Unit) {
            unlinkDeathRecipient()
            val recipient = IBinder.DeathRecipient {
                onDied()
            }
            deathRecipient = recipient
            try {
                binder.linkToDeath(recipient, 0)
            } catch (_: Throwable) {
                deathRecipient = null
                onDied()
            }
        }

        private fun unlinkDeathRecipient() {
            val current = deathRecipient ?: return
            deathRecipient = null
            try {
                binder.unlinkToDeath(current, 0)
            } catch (_: Throwable) {}
        }

        fun start(
            configJson: String,
            tunFd: ParcelFileDescriptor,
            workingDir: String,
            verificationSocksPort: Int,
            protectBinder: IBinder
        ): StartResult {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            var dupTun: ParcelFileDescriptor? = null
            return try {
                dupTun = tunFd.dup()
                data.writeInterfaceToken(DESCRIPTOR_WORKER)
                data.writeString(configJson)
                dupTun.writeToParcel(data, 0)
                data.writeString(workingDir)
                data.writeInt(verificationSocksPort)
                data.writeStrongBinder(protectBinder)
                binder.transact(TX_START, data, reply, 0)
                reply.readException()
                val ok = reply.readInt() == 1
                val msg = reply.readString() ?: ""
                val ver = reply.readString() ?: ""
                StartResult(ok, msg, ver)
            } finally {
                try { dupTun?.close() } catch (_: Throwable) {}
                data.recycle()
                reply.recycle()
            }
        }

        fun verify(): VerifyResult {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(DESCRIPTOR_WORKER)
                binder.transact(TX_VERIFY, data, reply, 0)
                reply.readException()
                val healthy = reply.readInt() == 1
                val latency = reply.readLong()
                val details = reply.readString() ?: ""
                VerifyResult(healthy, latency, details)
            } catch (t: Throwable) {
                VerifyResult(false, -1L, "Worker IPC verify failed: ${t.message}")
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        fun stats(): StatsResult {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(DESCRIPTOR_WORKER)
                binder.transact(TX_STATS, data, reply, 0)
                reply.readException()
                val running = reply.readInt() == 1
                val tx = reply.readLong()
                val rx = reply.readLong()
                val err = reply.readString()
                StatsResult(running, tx, rx, err)
            } catch (t: Throwable) {
                StatsResult(false, 0L, 0L, t.message)
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        fun stopAndUnbind() {
            unlinkDeathRecipient()
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR_WORKER)
                binder.transact(TX_STOP, data, reply, 0)
                reply.readException()
            } catch (_: Throwable) {
            } finally {
                data.recycle()
                reply.recycle()
                try {
                    context.unbindService(connection)
                } catch (_: Throwable) {}
            }
        }
    }

    suspend fun bindWorker(
        context: Context,
        serviceClass: Class<*>,
        timeoutMs: Long = 8000L
    ): WorkerConnection {
        val deferred = CompletableDeferred<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service != null && !deferred.isCompleted) {
                    deferred.complete(service)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!deferred.isCompleted) {
                    deferred.completeExceptionally(IllegalStateException("Worker service disconnected before handshake"))
                }
            }

            override fun onBindingDied(name: ComponentName?) {
                if (!deferred.isCompleted) {
                    deferred.completeExceptionally(IllegalStateException("Worker service binding died"))
                }
            }
        }

        val intent = Intent(context, serviceClass)
        val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!bound) {
            throw IllegalStateException("Failed to bind ${serviceClass.simpleName}")
        }

        val binder = try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (t: Throwable) {
            try { context.unbindService(connection) } catch (_: Throwable) {}
            throw t
        }

        return WorkerConnection(context.applicationContext, binder, connection)
    }
}
