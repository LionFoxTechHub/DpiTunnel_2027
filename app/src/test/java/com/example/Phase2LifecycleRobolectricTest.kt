package com.example

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.model.CoreEngineType
import com.example.model.DpiTweakSettings
import com.example.model.TunnelProfile
import com.example.model.TunnelProtocol
import com.example.model.VpnStatus
import com.example.service.CoreWorkerIpc
import com.example.service.XrayCoreService
import com.example.tunnel.EngineConfig
import com.example.tunnel.EngineState
import com.example.tunnel.SocketProtector
import com.example.tunnel.TunnelOrchestrator
import com.example.tunnel.engine.CoreWorkerLauncher
import com.example.tunnel.engine.CoreWorkerSession
import com.example.tunnel.engine.SingBoxEngine
import com.example.tunnel.engine.XrayEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.net.DatagramSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class Phase2LifecycleRobolectricTest {

    private class RecordingSocketProtector(var shouldProtect: Boolean = true) : SocketProtector {
        val protectedFds = mutableListOf<Int>()
        override fun protect(socket: Socket): Boolean = shouldProtect
        override fun protect(socket: DatagramSocket): Boolean = shouldProtect
        override fun protect(socketFd: Int): Boolean {
            protectedFds.add(socketFd)
            return shouldProtect
        }
    }

    private class FakeCoreWorkerSession(
        private val onStart: (SocketProtector) -> CoreWorkerIpc.StartResult = {
            CoreWorkerIpc.StartResult(true, "core running", "v-test")
        },
        private val onVerify: () -> CoreWorkerIpc.VerifyResult = {
            CoreWorkerIpc.VerifyResult(true, 18L, "tunneled HTTP probe ok")
        },
        private val onStats: () -> CoreWorkerIpc.StatsResult = {
            CoreWorkerIpc.StatsResult(true, 1024L, 4096L, null)
        }
    ) : CoreWorkerSession {
        var startCount = 0
        var stopCount = 0
        var lastConfigJson: String? = null
        private var deathCallback: (() -> Unit)? = null

        override fun start(
            configJson: String,
            tunFd: ParcelFileDescriptor,
            workingDir: String,
            verificationSocksPort: Int,
            protector: SocketProtector
        ): CoreWorkerIpc.StartResult {
            startCount++
            lastConfigJson = configJson
            return onStart(protector)
        }

        override fun verify(): CoreWorkerIpc.VerifyResult = onVerify()
        override fun stats(): CoreWorkerIpc.StatsResult = onStats()
        override fun stop() {
            stopCount++
        }

        override fun setOnProcessDiedListener(onDied: () -> Unit) {
            deathCallback = onDied
        }

        fun simulateProcessDeath() {
            deathCallback?.invoke()
        }
    }

    private fun openTempTunPfd(): ParcelFileDescriptor {
        val f = File.createTempFile("dpitunnel_test_tun", ".tmp")
        f.deleteOnExit()
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_WRITE)
    }

    private val validVlessProfile = TunnelProfile(
        id = "robo-vless",
        name = "Test VLESS Reality",
        protocol = TunnelProtocol.VLESS,
        preferredEngine = CoreEngineType.SING_BOX,
        serverHost = "de-fra.dpitunnel.net",
        serverPort = 443,
        uuidOrPassword = "7f2a1b9c-48d1-4ef3-9c02-e251a7e49121",
        dpiTweak = DpiTweakSettings(
            sniHost = "speedtest.net",
            httpPayload = "pbk=fakePubKey123&sid=1a2b3c4d"
        )
    )

    @Test
    fun testXrayCallResponseBase64Decoding() {
        val successJson = """{"success":true,"data":"1.8.26","error":""}"""
        val successB64 = Base64.encodeToString(successJson.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        val decodedOk = XrayCoreService.decodeCallResponse(successB64)
        assertTrue(decodedOk.success)
        assertEquals("1.8.26", decodedOk.data)
        assertEquals("", decodedOk.error)

        val errJson = """{"success":false,"data":"","error":"invalid inbound config"}"""
        val errB64 = Base64.encodeToString(errJson.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        val decodedErr = XrayCoreService.decodeCallResponse(errB64)
        assertFalse(decodedErr.success)
        assertEquals("invalid inbound config", decodedErr.error)

        val blank = XrayCoreService.decodeCallResponse("   ")
        assertFalse(blank.success)
    }

    @Test
    fun testSingBoxEngineLifecycleAndFailClosedProtection() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val protector = RecordingSocketProtector(shouldProtect = false)
        val fakeWorker = FakeCoreWorkerSession(
            onStart = { sockProtector ->
                if (!sockProtector.protect(55)) {
                    CoreWorkerIpc.StartResult(false, "Outbound socket protection failed (fail-closed)", "")
                } else {
                    CoreWorkerIpc.StartResult(true, "started", "1.13")
                }
            }
        )
        val engine = SingBoxEngine(CoreWorkerLauncher { fakeWorker })
        val tunPfd = openTempTunPfd()

        // Fail-closed when protect() returns false
        val failed = engine.start(
            EngineConfig(
                profile = validVlessProfile,
                tunFd = tunPfd,
                mtu = 1400,
                enableIpv6 = false,
                dnsServers = listOf("1.1.1.1"),
                appContext = context,
                workingDirPath = context.filesDir.absolutePath
            ),
            protector
        )
        assertTrue(failed.isFailure)
        assertTrue(fakeWorker.stopCount >= 1)
        assertEquals(listOf(55), protector.protectedFds)
        tunPfd.close()

        // Succeed and record stats when protect() returns true and verify() passes
        protector.shouldProtect = true
        val goodWorker = FakeCoreWorkerSession()
        val goodEngine = SingBoxEngine(CoreWorkerLauncher { goodWorker })
        val tunPfd2 = openTempTunPfd()
        val ok = goodEngine.start(
            EngineConfig(
                profile = validVlessProfile,
                tunFd = tunPfd2,
                mtu = 1400,
                enableIpv6 = false,
                dnsServers = listOf("1.1.1.1"),
                appContext = context,
                workingDirPath = context.filesDir.absolutePath
            ),
            protector
        )
        assertTrue(ok.isSuccess)
        val health = goodEngine.checkHealth()
        assertTrue(health.isHealthy)
        assertEquals(18L, health.latencyMs)
        assertEquals(1024L, goodEngine.trafficMonitor.totalUploadedBytes)
        assertEquals(4096L, goodEngine.trafficMonitor.totalDownloadedBytes)
        goodEngine.stop()
        assertTrue(goodEngine.state.value is EngineState.Disconnected)
        tunPfd2.close()
    }

    @Test
    fun testXrayEngineStartAndStop() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val protector = RecordingSocketProtector(shouldProtect = true)
        val fakeWorker = FakeCoreWorkerSession()
        val engine = XrayEngine(CoreWorkerLauncher { fakeWorker })
        val tunPfd = openTempTunPfd()

        val res = engine.start(
            EngineConfig(
                profile = validVlessProfile.copy(preferredEngine = CoreEngineType.XRAY_CORE),
                tunFd = tunPfd,
                mtu = 1400,
                enableIpv6 = false,
                dnsServers = listOf("1.1.1.1"),
                appContext = context,
                workingDirPath = context.filesDir.absolutePath
            ),
            protector
        )
        assertTrue(res.isSuccess)
        assertNotNull(fakeWorker.lastConfigJson)
        assertTrue(fakeWorker.lastConfigJson!!.contains("\"protocol\": \"tun\""))
        assertTrue(engine.checkHealth().isHealthy)

        engine.stop()
        assertEquals(1, fakeWorker.stopCount)
        tunPfd.close()
    }

    @Test
    fun testOrchestrator9StateLifecycleAndCleanDisconnect() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val observedStates = mutableListOf<VpnStatus>()
        val worker = FakeCoreWorkerSession()
        val engine = SingBoxEngine(CoreWorkerLauncher { worker })

        val orchestrator = TunnelOrchestrator(
            socketProtector = RecordingSocketProtector(true),
            onStateChanged = { state ->
                if (observedStates.lastOrNull() != state.status) {
                    observedStates.add(state.status)
                }
            },
            onNotificationUpdateNeeded = { _, _ -> },
            appContext = context,
            workingDirPath = context.filesDir.absolutePath,
            engineResolver = { Result.success(engine) },
            tickIntervalMs = 20L
        )

        val pfd = openTempTunPfd()
        val result = orchestrator.startTunnel(
            profile = validVlessProfile,
            mtu = 1400,
            enableIpv6 = false,
            dnsServers = listOf("1.1.1.1", "8.8.8.8"),
            tunEstablisher = { pfd }
        )
        assertTrue(result.isSuccess)

        orchestrator.stopTunnel("Test disconnect")

        assertEquals(
            listOf(
                VpnStatus.CONFIG_VALIDATION,
                VpnStatus.CORE_INITIALIZING,
                VpnStatus.TUN_ESTABLISHING,
                VpnStatus.CORE_RUNNING,
                VpnStatus.CONNECTIVITY_VERIFYING,
                VpnStatus.CONNECTED,
                VpnStatus.DISCONNECTING,
                VpnStatus.DISCONNECTED
            ),
            observedStates
        )
        assertEquals(1, worker.stopCount)
        orchestrator.release()
    }

    @Test
    fun testOrchestratorInvalidConfigFailsBeforeEstablishingTun() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val observedStates = mutableListOf<VpnStatus>()
        var tunEstablished = false

        val orchestrator = TunnelOrchestrator(
            socketProtector = RecordingSocketProtector(true),
            onStateChanged = { observedStates.add(it.status) },
            onNotificationUpdateNeeded = { _, _ -> },
            appContext = context,
            workingDirPath = context.filesDir.absolutePath
        )

        val badProfile = validVlessProfile.copy(
            dpiTweak = DpiTweakSettings(sniHost = "speedtest.net", httpPayload = "security=reality") // missing pbk=
        )

        val result = orchestrator.startTunnel(
            profile = badProfile,
            mtu = 1400,
            enableIpv6 = false,
            dnsServers = listOf("1.1.1.1"),
            tunEstablisher = {
                tunEstablished = true
                openTempTunPfd()
            }
        )

        assertTrue(result.isFailure)
        assertFalse("TUN must NOT be established when CONFIG_VALIDATION fails", tunEstablished)
        assertEquals(VpnStatus.ERROR, orchestrator.orchestratorState.value.status)
        assertFalse(observedStates.contains(VpnStatus.TUN_ESTABLISHING))
        assertFalse(observedStates.contains(VpnStatus.CONNECTED))
        orchestrator.release()
    }

    @Test
    fun testOrchestratorFailsClosedWhenVerificationFails() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val observedStates = mutableListOf<VpnStatus>()
        val worker = FakeCoreWorkerSession(
            onVerify = { CoreWorkerIpc.VerifyResult(false, -1L, "SOCKS5 handshake timed out") }
        )
        val engine = SingBoxEngine(CoreWorkerLauncher { worker })

        val orchestrator = TunnelOrchestrator(
            socketProtector = RecordingSocketProtector(true),
            onStateChanged = { observedStates.add(it.status) },
            onNotificationUpdateNeeded = { _, _ -> },
            appContext = context,
            workingDirPath = context.filesDir.absolutePath,
            engineResolver = { Result.success(engine) }
        )

        val pfd = openTempTunPfd()
        val result = orchestrator.startTunnel(
            profile = validVlessProfile,
            mtu = 1400,
            enableIpv6 = false,
            dnsServers = listOf("1.1.1.1"),
            tunEstablisher = { pfd }
        )

        assertTrue(result.isFailure)
        assertFalse("Must never report CONNECTED if connectivity verification failed", observedStates.contains(VpnStatus.CONNECTED))
        assertEquals(VpnStatus.ERROR, orchestrator.orchestratorState.value.status)
        assertTrue(worker.stopCount >= 1)
        orchestrator.release()
    }

    @Test
    fun testOrchestratorCrashRecoveryAttemptsRestartAndFailsClosed() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val verifyCalls = AtomicInteger(0)
        val worker = FakeCoreWorkerSession(
            onVerify = {
                val n = verifyCalls.incrementAndGet()
                // Initial connect (call 1) succeeds; subsequent periodic health checks fail to trigger crash recovery
                if (n == 1) {
                    CoreWorkerIpc.VerifyResult(true, 12L, "initial ok")
                } else {
                    CoreWorkerIpc.VerifyResult(false, -1L, "core process died")
                }
            },
            onStats = {
                if (verifyCalls.get() == 0) {
                    CoreWorkerIpc.StatsResult(true, 0L, 0L, null)
                } else {
                    CoreWorkerIpc.StatsResult(false, 0L, 0L, "native crash")
                }
            }
        )
        val engine = SingBoxEngine(CoreWorkerLauncher { worker })

        val orchestrator = TunnelOrchestrator(
            socketProtector = RecordingSocketProtector(true),
            onStateChanged = {},
            onNotificationUpdateNeeded = { _, _ -> },
            appContext = context,
            workingDirPath = context.filesDir.absolutePath,
            engineResolver = { Result.success(engine) },
            tickIntervalMs = 5L
        )

        val pfd = openTempTunPfd()
        val started = orchestrator.startTunnel(
            profile = validVlessProfile,
            mtu = 1400,
            enableIpv6 = false,
            dnsServers = listOf("1.1.1.1"),
            tunEstablisher = { pfd }
        )
        assertTrue(started.isSuccess)

        // Wait briefly for 30 accelerated ticks (2 x 15-tick health checks -> crash recovery attempt -> fail closed)
        val deadline = System.currentTimeMillis() + 5000L
        while (orchestrator.orchestratorState.value.status != VpnStatus.ERROR &&
            System.currentTimeMillis() < deadline
        ) {
            delay(25L)
        }

        assertEquals(VpnStatus.ERROR, orchestrator.orchestratorState.value.status)
        assertTrue("Expected restart attempt during crash recovery", worker.startCount >= 2)
        orchestrator.release()
    }

    @Test
    fun testImmediateWorkerDeathTriggersCrashRecoveryWithoutPollingDelay() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val verifyCalls = AtomicInteger(0)
        val worker = FakeCoreWorkerSession(
            onVerify = {
                val n = verifyCalls.incrementAndGet()
                if (n == 1) {
                    CoreWorkerIpc.VerifyResult(true, 10L, "initial ok")
                } else {
                    CoreWorkerIpc.VerifyResult(false, -1L, "worker process still dead")
                }
            }
        )
        val engine = SingBoxEngine(CoreWorkerLauncher { worker })

        val orchestrator = TunnelOrchestrator(
            socketProtector = RecordingSocketProtector(true),
            onStateChanged = {},
            onNotificationUpdateNeeded = { _, _ -> },
            appContext = context,
            workingDirPath = context.filesDir.absolutePath,
            engineResolver = { Result.success(engine) },
            tickIntervalMs = 60_000L // Long tick interval proves DeathRecipient triggers recovery immediately
        )

        val pfd = openTempTunPfd()
        val started = orchestrator.startTunnel(
            profile = validVlessProfile,
            mtu = 1400,
            enableIpv6 = false,
            dnsServers = listOf("1.1.1.1"),
            tunEstablisher = { pfd }
        )
        assertTrue(started.isSuccess)

        worker.simulateProcessDeath()

        val deadline = System.currentTimeMillis() + 3000L
        while (orchestrator.orchestratorState.value.status != VpnStatus.ERROR &&
            System.currentTimeMillis() < deadline
        ) {
            delay(20L)
        }

        assertEquals(VpnStatus.ERROR, orchestrator.orchestratorState.value.status)
        assertTrue("Expected immediate restart attempt on DeathRecipient callback", worker.startCount >= 2)
        orchestrator.release()
    }

    @Test
    fun testLegacyStartTunnelClosesPreCreatedFdOnValidationFailure() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val orchestrator = TunnelOrchestrator(
            socketProtector = RecordingSocketProtector(true),
            onStateChanged = {},
            onNotificationUpdateNeeded = { _, _ -> },
            appContext = context,
            workingDirPath = context.filesDir.absolutePath
        )

        val preCreatedPfd = openTempTunPfd()
        val badProfile = validVlessProfile.copy(uuidOrPassword = "invalid-uuid")
        val res = orchestrator.startTunnel(
            profile = badProfile,
            tunFd = preCreatedPfd,
            mtu = 1400,
            enableIpv6 = false,
            dnsServers = listOf("1.1.1.1")
        )

        assertTrue(res.isFailure)
        assertFalse("Legacy startTunnel must close caller's pre-created PFD on pre-TUN validation failure", preCreatedPfd.fileDescriptor.valid())
        orchestrator.release()
    }
}
