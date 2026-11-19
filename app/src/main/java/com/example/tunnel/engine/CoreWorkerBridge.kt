package com.example.tunnel.engine

import android.content.Context
import android.os.ParcelFileDescriptor
import com.example.service.CoreWorkerIpc
import com.example.tunnel.SocketProtector

/**
 * Abstraction over the process-isolated Binder IPC worker connection so production code
 * always executes inside `:singbox` / `:xray` native processes while unit tests can
 * verify orchestration, fail-closed protection, and crash recovery deterministically.
 */
interface CoreWorkerSession {
    fun start(
        configJson: String,
        tunFd: ParcelFileDescriptor,
        workingDir: String,
        verificationSocksPort: Int,
        protector: SocketProtector
    ): CoreWorkerIpc.StartResult

    fun verify(): CoreWorkerIpc.VerifyResult

    fun stats(): CoreWorkerIpc.StatsResult

    fun stop()

    fun setOnProcessDiedListener(onDied: () -> Unit) {}
}

fun interface CoreWorkerLauncher {
    suspend fun launch(context: Context): CoreWorkerSession
}

class BinderCoreWorkerSession(
    private val connection: CoreWorkerIpc.WorkerConnection
) : CoreWorkerSession {

    override fun start(
        configJson: String,
        tunFd: ParcelFileDescriptor,
        workingDir: String,
        verificationSocksPort: Int,
        protector: SocketProtector
    ): CoreWorkerIpc.StartResult {
        val protectBinder = CoreWorkerIpc.SocketProtectorBinder(protector)
        return connection.start(
            configJson = configJson,
            tunFd = tunFd,
            workingDir = workingDir,
            verificationSocksPort = verificationSocksPort,
            protectBinder = protectBinder
        )
    }

    override fun verify(): CoreWorkerIpc.VerifyResult = connection.verify()

    override fun stats(): CoreWorkerIpc.StatsResult = connection.stats()

    override fun stop() = connection.stopAndUnbind()

    override fun setOnProcessDiedListener(onDied: () -> Unit) {
        connection.setOnProcessDiedListener(onDied)
    }
}
