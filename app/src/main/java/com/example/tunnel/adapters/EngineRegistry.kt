package com.example.tunnel.adapters

import com.example.model.CoreEngineType
import com.example.model.TunnelProfile
import com.example.model.TunnelProtocol
import com.example.tunnel.EngineCapability
import com.example.tunnel.TunnelEngine
import com.example.tunnel.config.SingBoxConfigBuilder
import com.example.tunnel.config.TransportType
import com.example.tunnel.config.XrayConfigBuilder
import com.example.tunnel.engine.*

object EngineRegistry {

    private val singBoxEngine = SingBoxEngine()
    private val xrayEngine = XrayEngine()

    private val phase1Adapters: List<TunnelEngine> = listOf(
        HttpProxyEngine(),
        SshEngine(),
        SocksEngine(),
        DnsTunnelEngine(),
        OpenVpnEngine(),
        HysteriaEngine()
    )

    private val customAdapters = mutableListOf<TunnelEngine>()

    fun register(engine: TunnelEngine) {
        if (!customAdapters.contains(engine) &&
            engine !in phase1Adapters &&
            engine !== singBoxEngine &&
            engine !== xrayEngine
        ) {
            customAdapters.add(engine)
        }
    }

    /**
     * Explicit engine selection with strict protocol, transport, and configuration capability checks.
     * Never silently falls back to an incompatible engine or protocol.
     */
    fun resolveEngine(profile: TunnelProfile): Result<TunnelEngine> {
        return when (profile.preferredEngine) {
            CoreEngineType.SING_BOX -> {
                if (!singBoxEngine.supportedProtocols.contains(profile.protocol)) {
                    Result.failure(
                        UnsupportedOperationException(
                            "sing-box engine does not support protocol ${profile.protocol.displayName}"
                        )
                    )
                } else {
                    val parsed = SingBoxConfigBuilder.validateProfile(profile)
                    if (parsed.isFailure) {
                        Result.failure(parsed.exceptionOrNull()!!)
                    } else {
                        Result.success(singBoxEngine)
                    }
                }
            }
            CoreEngineType.XRAY_CORE -> {
                if (!xrayEngine.supportedProtocols.contains(profile.protocol)) {
                    Result.failure(
                        UnsupportedOperationException(
                            "Xray-core engine does not support protocol ${profile.protocol.displayName}"
                        )
                    )
                } else {
                    val parsed = XrayConfigBuilder.validateProfile(profile)
                    if (parsed.isFailure) {
                        Result.failure(parsed.exceptionOrNull()!!)
                    } else {
                        Result.success(xrayEngine)
                    }
                }
            }
            CoreEngineType.AUTO -> {
                resolveAutoEngine(profile)
            }
        }
    }

    private fun resolveAutoEngine(profile: TunnelProfile): Result<TunnelEngine> {
        // Explicit Phase 1 engines first
        val phase1 = phase1Adapters.firstOrNull { it.supportedProtocols.contains(profile.protocol) }
        if (phase1 != null) {
            return Result.success(phase1)
        }

        // Phase 2 core selection based on protocol and transport capability
        if (profile.protocol == TunnelProtocol.V2RAY) {
            val v2rayValidation = XrayConfigBuilder.validateProfile(profile)
            return if (v2rayValidation.isFailure) {
                Result.failure(v2rayValidation.exceptionOrNull()!!)
            } else {
                Result.success(xrayEngine)
            }
        }

        if (singBoxEngine.supportedProtocols.contains(profile.protocol) ||
            xrayEngine.supportedProtocols.contains(profile.protocol)
        ) {
            val xrayParsed = XrayConfigBuilder.validateProfile(profile)
            if (xrayParsed.isFailure) {
                return Result.failure(xrayParsed.exceptionOrNull()!!)
            }
            if (xrayParsed.getOrNull()?.transport == TransportType.XHTTP) {
                return Result.success(xrayEngine)
            }
            if (singBoxEngine.supportedProtocols.contains(profile.protocol)) {
                val sbParsed = SingBoxConfigBuilder.validateProfile(profile)
                return if (sbParsed.isFailure) {
                    Result.failure(sbParsed.exceptionOrNull()!!)
                } else {
                    Result.success(singBoxEngine)
                }
            }
            return Result.success(xrayEngine)
        }

        val custom = customAdapters.firstOrNull { it.supportedProtocols.contains(profile.protocol) }
        if (custom != null) {
            return Result.success(custom)
        }

        return Result.failure(
            UnsupportedOperationException(
                "Protocol ${profile.protocol.displayName} is unsupported by all registered engines"
            )
        )
    }

    /**
     * Resolves an engine by protocol and optional explicit [CoreEngineType].
     * Preserves compatibility for Phase 1/2 lookup callers while honoring explicit engine choice.
     */
    fun getEngineForProtocol(
        protocol: TunnelProtocol,
        preferredEngine: CoreEngineType = CoreEngineType.AUTO
    ): TunnelEngine? {
        return when (preferredEngine) {
            CoreEngineType.SING_BOX -> singBoxEngine.takeIf { it.supportedProtocols.contains(protocol) }
            CoreEngineType.XRAY_CORE -> xrayEngine.takeIf { it.supportedProtocols.contains(protocol) }
            CoreEngineType.AUTO -> {
                phase1Adapters.firstOrNull { it.supportedProtocols.contains(protocol) }
                    ?: if (protocol == TunnelProtocol.V2RAY) xrayEngine
                    else listOf(singBoxEngine, xrayEngine).plus(customAdapters)
                        .firstOrNull { it.supportedProtocols.contains(protocol) }
            }
        }
    }

    fun getAllEngines(): List<TunnelEngine> =
        phase1Adapters + listOf(singBoxEngine, xrayEngine) + customAdapters

    fun getCapabilities(): List<EngineCapability> {
        return listOf(
            EngineCapability(
                engineId = "sing-box",
                engineName = "sing-box (libbox 2.1.5 / sing-box 1.13)",
                coreType = CoreEngineType.SING_BOX,
                supportedProtocols = singBoxEngine.supportedProtocols,
                supportedTransports = singBoxEngine.supportedTransports,
                supportedSecurityModes = listOf("none", "tls", "reality"),
                unsupportedFeatures = listOf("xhttp", "wireguard_kernel")
            ),
            EngineCapability(
                engineId = "xray-core",
                engineName = "Xray-core (libxray 26.6.27)",
                coreType = CoreEngineType.XRAY_CORE,
                supportedProtocols = xrayEngine.supportedProtocols,
                supportedTransports = xrayEngine.supportedTransports,
                supportedSecurityModes = listOf("none", "tls", "reality"),
                unsupportedFeatures = listOf("wireguard_kernel")
            )
        ) + phase1Adapters.map { adapter ->
            EngineCapability(
                engineId = adapter.engineName.lowercase(),
                engineName = adapter.engineName,
                coreType = null,
                supportedProtocols = adapter.supportedProtocols,
                supportedTransports = adapter.supportedTransports,
                supportedSecurityModes = listOf("tls", "payload"),
                unsupportedFeatures = emptyList()
            )
        }
    }
}
