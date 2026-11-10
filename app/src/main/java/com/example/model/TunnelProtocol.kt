package com.example.model

import androidx.compose.ui.graphics.Color
import com.example.ui.theme.*

enum class ProtocolPhaseStatus(val label: String, val isPhase1Active: Boolean) {
    IMPLEMENTED_PHASE_1("Active (Phase 1)", true),
    PLANNED_PHASE_2("Planned (Phase 2 Core)", false),
    REQUIRES_KERNEL("Kernel WireGuard Required", false)
}

enum class TunnelProtocol(
    val displayName: String,
    val shortName: String,
    val defaultPort: Int,
    val defaultObfuscation: String,
    val badgeColor: Color,
    val description: String,
    val phaseStatus: ProtocolPhaseStatus
) {
    HTTP(
        displayName = "HTTP Custom",
        shortName = "HTTP",
        defaultPort = 8080,
        defaultObfuscation = "Custom Bug Host + Payload",
        badgeColor = ColorHttp,
        description = "HTTP proxy injector with customizable header templates and host fronting",
        phaseStatus = ProtocolPhaseStatus.IMPLEMENTED_PHASE_1
    ),
    SSH(
        displayName = "SSH Tunnel",
        shortName = "SSH",
        defaultPort = 22,
        defaultObfuscation = "HTTP Payload Injection",
        badgeColor = ColorSsh,
        description = "Secure Shell tunnel encapsulated inside injected HTTP WebSocket headers",
        phaseStatus = ProtocolPhaseStatus.IMPLEMENTED_PHASE_1
    ),
    SOCKS(
        displayName = "SOCKS5",
        shortName = "SOCKS",
        defaultPort = 1080,
        defaultObfuscation = "TLS Wrapper",
        badgeColor = ColorSocks,
        description = "Standard SOCKS5 wrapped in obfuscated TLS to evade stateful inspection",
        phaseStatus = ProtocolPhaseStatus.IMPLEMENTED_PHASE_1
    ),
    SLOWDNS(
        displayName = "SlowDNS",
        shortName = "DNS",
        defaultPort = 53,
        defaultObfuscation = "DNS TXT Query Tunneling",
        badgeColor = ColorSlowDns,
        description = "Encapsulates IP packets inside standard UDP 53 DNS requests to bypass firewalls",
        phaseStatus = ProtocolPhaseStatus.IMPLEMENTED_PHASE_1
    ),
    OPENVPN(
        displayName = "OpenVPN",
        shortName = "OVPN",
        defaultPort = 1194,
        defaultObfuscation = "XOR Scramble / TLS-Crypt",
        badgeColor = ColorOpenVpn,
        description = "Industry-standard VPN tunnel augmented with XOR header scrambling",
        phaseStatus = ProtocolPhaseStatus.IMPLEMENTED_PHASE_1
    ),
    HYSTERIA1(
        displayName = "Hysteria 1",
        shortName = "HY1",
        defaultPort = 36712,
        defaultObfuscation = "QUIC Frame Scrambling",
        badgeColor = ColorHysteria1,
        description = "UDP-based tunnel designed for lossy networks with aggressive CC algorithm",
        phaseStatus = ProtocolPhaseStatus.IMPLEMENTED_PHASE_1
    ),
    HYSTERIA2(
        displayName = "Hysteria 2",
        shortName = "HY2",
        defaultPort = 8443,
        defaultObfuscation = "Salamander UDP Obfs",
        badgeColor = ColorHysteria2,
        description = "Next-gen QUIC protocol with Salamander obfuscation and bandwidth acceleration",
        phaseStatus = ProtocolPhaseStatus.IMPLEMENTED_PHASE_1
    ),
    VLESS(
        displayName = "VLESS",
        shortName = "VLESS",
        defaultPort = 443,
        defaultObfuscation = "Reality + TLS Splitting",
        badgeColor = ColorVless,
        description = "Lightweight stateless proxy protocol with TLS Reality camouflage",
        phaseStatus = ProtocolPhaseStatus.PLANNED_PHASE_2
    ),
    VMESS(
        displayName = "VMess",
        shortName = "VMESS",
        defaultPort = 443,
        defaultObfuscation = "WebSocket + AEAD Header",
        badgeColor = ColorVmess,
        description = "Encrypted transport protocol for V2Ray ecosystem with payload padding",
        phaseStatus = ProtocolPhaseStatus.PLANNED_PHASE_2
    ),
    SHADOWSOCKS(
        displayName = "Shadowsocks",
        shortName = "SS",
        defaultPort = 8388,
        defaultObfuscation = "Simple-Obfs (HTTP/TLS)",
        badgeColor = ColorShadowsocks,
        description = "High-performance encrypted SOCKS5 proxy with custom stream ciphers",
        phaseStatus = ProtocolPhaseStatus.PLANNED_PHASE_2
    ),
    TROJAN(
        displayName = "Trojan",
        shortName = "TROJAN",
        defaultPort = 443,
        defaultObfuscation = "HTTPS Camouflage",
        badgeColor = ColorTrojan,
        description = "Unidentifiable mechanism that imitates HTTPS traffic with valid TLS certificate",
        phaseStatus = ProtocolPhaseStatus.PLANNED_PHASE_2
    ),
    V2RAY(
        displayName = "V2Ray",
        shortName = "V2RAY",
        defaultPort = 443,
        defaultObfuscation = "mKCP + Seed Obfs",
        badgeColor = ColorV2Ray,
        description = "Comprehensive multi-protocol proxy core with multiplexing and fake DNS",
        phaseStatus = ProtocolPhaseStatus.PLANNED_PHASE_2
    ),
    WIREGUARD(
        displayName = "WireGuard",
        shortName = "WG",
        defaultPort = 51820,
        defaultObfuscation = "Amnezia Header Padding",
        badgeColor = ColorWireGuard,
        description = "Fast modern kernel VPN with AmneziaWG junk packet padding against DPI",
        phaseStatus = ProtocolPhaseStatus.REQUIRES_KERNEL
    )
}
