package com.example.model

enum class TcpFragmentMode(val label: String, val description: String) {
    NONE("Disabled", "Standard packet transmission without fragmentation"),
    SPLIT_AT_SNI("Split at SNI", "Fragments ClientHello packet at the SNI domain boundary"),
    TWO_BYTE_CHUNKS("2-Byte Header Chunk", "Sends first 2 bytes separately to break DPI pattern matching"),
    DELAYED_SPLIT("Delayed Fragment (15ms)", "Splits handshake with micro-delay to bypass DPI reassembly caches")
}

enum class DohProvider(val displayName: String, val endpoint: String) {
    CLOUDFLARE("Cloudflare DoH", "https://cloudflare-dns.com/dns-query"),
    GOOGLE("Google Public DNS", "https://dns.google/resolve"),
    ADGUARD("AdGuard DNS", "https://dns.adguard-dns.com/dns-query"),
    QUAD9("Quad9 DNS", "https://dns.quad9.net/dns-query")
}

data class DpiTweakSettings(
    val sniHost: String = "cloudflare.com",
    val tcpFragmentMode: TcpFragmentMode = TcpFragmentMode.SPLIT_AT_SNI,
    val fragmentDelayMs: Long = 15L,
    val fakeHttpRequest: Boolean = true,
    val httpPayload: String = DEFAULT_PAYLOAD,
    val dohProvider: DohProvider = DohProvider.CLOUDFLARE,
    val tlsAlpn: String = "h2,http/1.1",
    val badChecksumEvasion: Boolean = true,
    val quicJunkPadding: Boolean = true,
    val customUserAgent: String = "Mozilla/5.0 (Android; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0"
) {
    companion object {
        const val DEFAULT_PAYLOAD = "CONNECT [host_port] HTTP/1.1[crlf]Host: [host][crlf]X-Forwarded-For: [host][crlf]User-Agent: [ua][crlf]Connection: Upgrade[crlf][crlf]"
        const val WEBSOCKET_PAYLOAD = "GET /tunnel HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf]Sec-WebSocket-Key: [raw][crlf][crlf]"
        const val DIRECT_FRONTING_PAYLOAD = "POST / HTTP/1.1[crlf]Host: [host][crlf]X-Online-Host: [host][crlf]Connection: Keep-Alive[crlf][crlf]"
    }
}
