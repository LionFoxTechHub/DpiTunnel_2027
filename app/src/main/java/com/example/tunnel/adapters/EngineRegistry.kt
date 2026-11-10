package com.example.tunnel.adapters

import com.example.model.TunnelProtocol
import com.example.tunnel.TunnelEngine
import com.example.tunnel.engine.*

object EngineRegistry {

    private val adapters = mutableListOf<TunnelEngine>()

    init {
        // Register specialized HTTP Tweak Phase 1 engines first
        register(HttpProxyEngine())
        register(SshEngine())
        register(SocksEngine())
        register(DnsTunnelEngine())
        register(OpenVpnEngine())
        register(HysteriaEngine())
        // Fallback stream adapter
        register(DirectSocketEngineAdapter())
    }

    fun register(engine: TunnelEngine) {
        if (!adapters.contains(engine)) {
            adapters.add(engine)
        }
    }

    fun getEngineForProtocol(protocol: TunnelProtocol): TunnelEngine? {
        return adapters.firstOrNull { it.supportedProtocols.contains(protocol) }
    }

    fun getAllEngines(): List<TunnelEngine> = adapters.toList()
}
