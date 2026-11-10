package com.example.data

import android.content.Context
import com.example.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.UUID

object TunnelRepository {

    private val defaultProfiles = listOf(
        TunnelProfile(
            id = "cfg-vless-de",
            name = "Frankfurt VLESS Reality",
            protocol = TunnelProtocol.VLESS,
            serverHost = "de-fra.dpitunnel.net",
            serverPort = 443,
            uuidOrPassword = "7f2a1b9c-48d1-4ef3-9c02-e251a7e49121",
            countryCode = "DE",
            cityName = "Frankfurt",
            pingMs = -1,
            dpiTweak = DpiTweakSettings(
                sniHost = "speedtest.net",
                tcpFragmentMode = TcpFragmentMode.SPLIT_AT_SNI,
                fragmentDelayMs = 12L
            )
        ),
        TunnelProfile(
            id = "cfg-vmess-sg",
            name = "Singapore VMess WS",
            protocol = TunnelProtocol.VMESS,
            serverHost = "sg-sin.dpitunnel.net",
            serverPort = 443,
            uuidOrPassword = "98a1c243-7bb2-4110-85f0-cc2019488a0b",
            countryCode = "SG",
            cityName = "Singapore",
            pingMs = -1,
            dpiTweak = DpiTweakSettings(
                sniHost = "cloudflare.com",
                tcpFragmentMode = TcpFragmentMode.DELAYED_SPLIT,
                fragmentDelayMs = 20L
            )
        ),
        TunnelProfile(
            id = "cfg-ss-jp",
            name = "Tokyo Shadowsocks AEAD",
            protocol = TunnelProtocol.SHADOWSOCKS,
            serverHost = "jp-tyo.dpitunnel.net",
            serverPort = 8388,
            uuidOrPassword = "chacha20-ietf-poly1305:DpiSecretKey2026",
            countryCode = "JP",
            cityName = "Tokyo",
            pingMs = -1,
            dpiTweak = DpiTweakSettings(
                sniHost = "zoom.us",
                tcpFragmentMode = TcpFragmentMode.TWO_BYTE_CHUNKS
            )
        ),
        TunnelProfile(
            id = "cfg-trojan-gb",
            name = "London Trojan Stealth",
            protocol = TunnelProtocol.TROJAN,
            serverHost = "gb-lon.dpitunnel.net",
            serverPort = 443,
            uuidOrPassword = "pass_dpi_trojan_secure_99",
            countryCode = "GB",
            cityName = "London",
            pingMs = -1,
            dpiTweak = DpiTweakSettings(
                sniHost = "microsoft.com",
                tcpFragmentMode = TcpFragmentMode.SPLIT_AT_SNI
            )
        ),
        TunnelProfile(
            id = "cfg-hy2-us",
            name = "US East Hysteria 2 Turbo",
            protocol = TunnelProtocol.HYSTERIA2,
            serverHost = "us-iad.dpitunnel.net",
            serverPort = 8443,
            uuidOrPassword = "auth_hy2_ultra_speed",
            countryCode = "US",
            cityName = "Ashburn",
            pingMs = -1,
            dpiTweak = DpiTweakSettings(
                quicJunkPadding = true,
                badChecksumEvasion = true
            )
        ),
        TunnelProfile(
            id = "cfg-hy1-nl",
            name = "Amsterdam Hysteria 1",
            protocol = TunnelProtocol.HYSTERIA1,
            serverHost = "nl-ams.dpitunnel.net",
            serverPort = 36712,
            uuidOrPassword = "hy1_auth_amsterdam",
            countryCode = "NL",
            cityName = "Amsterdam",
            pingMs = -1
        ),
        TunnelProfile(
            id = "cfg-wg-ch",
            name = "Zurich WireGuard Amnezia",
            protocol = TunnelProtocol.WIREGUARD,
            serverHost = "ch-zrh.dpitunnel.net",
            serverPort = 51820,
            uuidOrPassword = "wg_pub_key_obfuscated_amnezia_padding",
            countryCode = "CH",
            cityName = "Zurich",
            pingMs = -1,
            dpiTweak = DpiTweakSettings(
                quicJunkPadding = true
            )
        ),
        TunnelProfile(
            id = "cfg-ssh-au",
            name = "Sydney SSH Payload",
            protocol = TunnelProtocol.SSH,
            serverHost = "au-syd.dpitunnel.net",
            serverPort = 22,
            uuidOrPassword = "root:DpiTunnelSsh2026",
            countryCode = "AU",
            cityName = "Sydney",
            pingMs = -1,
            dpiTweak = DpiTweakSettings(
                sniHost = "freefiremobile.com",
                fakeHttpRequest = true,
                httpPayload = DpiTweakSettings.DEFAULT_PAYLOAD
            )
        ),
        TunnelProfile(
            id = "cfg-ovpn-fr",
            name = "Paris OpenVPN XOR",
            protocol = TunnelProtocol.OPENVPN,
            serverHost = "fr-par.dpitunnel.net",
            serverPort = 1194,
            uuidOrPassword = "ovpn_xor_scramble_key",
            countryCode = "FR",
            cityName = "Paris",
            pingMs = -1
        ),
        TunnelProfile(
            id = "cfg-dns-kr",
            name = "Seoul SlowDNS Port 53",
            protocol = TunnelProtocol.SLOWDNS,
            serverHost = "kr-sel.dpitunnel.net",
            serverPort = 53,
            uuidOrPassword = "ns1.dpitunnel.net",
            countryCode = "KR",
            cityName = "Seoul",
            pingMs = -1
        ),
        TunnelProfile(
            id = "cfg-http-us",
            name = "New York HTTP Injector",
            protocol = TunnelProtocol.HTTP,
            serverHost = "us-nyc.dpitunnel.net",
            serverPort = 8080,
            uuidOrPassword = "",
            countryCode = "US",
            cityName = "New York",
            pingMs = -1,
            dpiTweak = DpiTweakSettings(
                sniHost = "cloudflare.com",
                fakeHttpRequest = true,
                httpPayload = DpiTweakSettings.WEBSOCKET_PAYLOAD
            )
        ),
        TunnelProfile(
            id = "cfg-socks-se",
            name = "Stockholm SOCKS5 TLS",
            protocol = TunnelProtocol.SOCKS,
            serverHost = "se-sto.dpitunnel.net",
            serverPort = 1080,
            uuidOrPassword = "user:sockspass2026",
            countryCode = "SE",
            cityName = "Stockholm",
            pingMs = -1
        ),
        TunnelProfile(
            id = "cfg-v2ray-hk",
            name = "Hong Kong V2Ray Core",
            protocol = TunnelProtocol.V2RAY,
            serverHost = "hk-hkg.dpitunnel.net",
            serverPort = 443,
            uuidOrPassword = "55a1d019-3e54-4a21-9988-cb94017ba3f8",
            countryCode = "HK",
            cityName = "Hong Kong",
            pingMs = -1,
            dpiTweak = DpiTweakSettings(
                sniHost = "fast.com",
                tcpFragmentMode = TcpFragmentMode.SPLIT_AT_SNI
            )
        )
    )

    private val _profiles = MutableStateFlow<List<TunnelProfile>>(defaultProfiles)
    val profiles: StateFlow<List<TunnelProfile>> = _profiles.asStateFlow()

    private val _selectedProfile = MutableStateFlow<TunnelProfile>(defaultProfiles.first())
    val selectedProfile: StateFlow<TunnelProfile> = _selectedProfile.asStateFlow()

    private var storageContext: Context? = null

    fun initialize(context: Context) {
        storageContext = context.applicationContext
        loadFromDisk()
    }

    fun selectProfile(profile: TunnelProfile) {
        _selectedProfile.value = profile
    }

    fun addCustomProfile(profile: TunnelProfile) {
        _profiles.value = listOf(profile) + _profiles.value
        _selectedProfile.value = profile
        persistToDisk()
    }

    fun updateProfile(updated: TunnelProfile) {
        _profiles.value = _profiles.value.map { if (it.id == updated.id) updated else it }
        if (_selectedProfile.value.id == updated.id) {
            _selectedProfile.value = updated
        }
        persistToDisk()
    }

    fun deleteProfile(id: String) {
        _profiles.value = _profiles.value.filter { it.id != id }
        if (_selectedProfile.value.id == id && _profiles.value.isNotEmpty()) {
            _selectedProfile.value = _profiles.value.first()
        }
        persistToDisk()
    }

    fun toggleFavorite(id: String) {
        _profiles.value = _profiles.value.map {
            if (it.id == id) it.copy(isFavorite = !it.isFavorite) else it
        }
        persistToDisk()
    }

    suspend fun pingProfile(profile: TunnelProfile): Int = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        var latency = -1
        try {
            Socket().use { socket ->
                socket.soTimeout = 2500
                socket.connect(InetSocketAddress(profile.serverHost, profile.serverPort), 2500)
                latency = (System.currentTimeMillis() - start).toInt()
            }
        } catch (_: Exception) {
            // Actual measurement: -1 when unreachable
            latency = -1
        }
        updateProfile(profile.copy(pingMs = latency))
        latency
    }

    suspend fun pingAllProfiles(onProgress: (Int, Int) -> Unit) {
        val currentList = _profiles.value
        currentList.forEachIndexed { index, profile ->
            onProgress(index + 1, currentList.size)
            pingProfile(profile)
        }
    }

    private fun persistToDisk() {
        val context = storageContext ?: return
        try {
            val file = File(context.filesDir, "tunnel_profiles.json")
            val array = JSONArray()
            _profiles.value.forEach { profile ->
                val obj = JSONObject(exportConfigToJson(profile))
                obj.put("id", profile.id)
                obj.put("isCustom", profile.isCustom)
                obj.put("isFavorite", profile.isFavorite)
                array.put(obj)
            }
            file.writeText(array.toString(2))
        } catch (_: Exception) {}
    }

    private fun loadFromDisk() {
        val context = storageContext ?: return
        try {
            val file = File(context.filesDir, "tunnel_profiles.json")
            if (!file.exists()) return
            val text = file.readText()
            val array = JSONArray(text)
            val loaded = mutableListOf<TunnelProfile>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val profile = importConfigFromString(obj.toString())
                if (profile != null) {
                    val fullProfile = profile.copy(
                        id = obj.optString("id", profile.id),
                        isCustom = obj.optBoolean("isCustom", true),
                        isFavorite = obj.optBoolean("isFavorite", false)
                    )
                    loaded.add(fullProfile)
                }
            }
            if (loaded.isNotEmpty()) {
                _profiles.value = loaded
                _selectedProfile.value = loaded.first()
            }
        } catch (_: Exception) {}
    }

    fun importConfigFromString(rawText: String): TunnelProfile? {
        val text = rawText.trim()
        try {
            if (text.startsWith("{") && text.endsWith("}")) {
                val json = JSONObject(text)
                val protocolName = json.optString("protocol", "VLESS")
                val protocol = TunnelProtocol.entries.firstOrNull { it.name.equals(protocolName, true) }
                    ?: TunnelProtocol.VLESS

                return TunnelProfile(
                    id = json.optString("id", UUID.randomUUID().toString()),
                    name = json.optString("name", "Imported ${protocol.displayName}"),
                    protocol = protocol,
                    serverHost = json.optString("serverHost", "127.0.0.1"),
                    serverPort = json.optInt("serverPort", protocol.defaultPort),
                    uuidOrPassword = json.optString("uuidOrPassword", ""),
                    countryCode = json.optString("countryCode", "US"),
                    cityName = json.optString("cityName", "Custom"),
                    isCustom = true,
                    dpiTweak = DpiTweakSettings(
                        sniHost = json.optString("sniHost", "cloudflare.com"),
                        fakeHttpRequest = json.optBoolean("fakeHttpRequest", true),
                        httpPayload = json.optString("httpPayload", DpiTweakSettings.DEFAULT_PAYLOAD)
                    )
                )
            } else if (text.contains("://")) {
                val uri = URI(text)
                val scheme = uri.scheme?.lowercase() ?: ""
                val protocol = when (scheme) {
                    "vless" -> TunnelProtocol.VLESS
                    "vmess" -> TunnelProtocol.VMESS
                    "ss" -> TunnelProtocol.SHADOWSOCKS
                    "trojan" -> TunnelProtocol.TROJAN
                    "hy2", "hysteria2" -> TunnelProtocol.HYSTERIA2
                    "hy", "hysteria" -> TunnelProtocol.HYSTERIA1
                    "ssh" -> TunnelProtocol.SSH
                    "http", "https" -> TunnelProtocol.HTTP
                    else -> TunnelProtocol.VLESS
                }

                val host = uri.host ?: "127.0.0.1"
                val port = if (uri.port > 0) uri.port else protocol.defaultPort
                val userInfo = uri.userInfo ?: ""
                val fragment = uri.fragment ?: "Custom ${protocol.displayName}"

                return TunnelProfile(
                    id = UUID.randomUUID().toString(),
                    name = fragment,
                    protocol = protocol,
                    serverHost = host,
                    serverPort = port,
                    uuidOrPassword = userInfo,
                    countryCode = "US",
                    cityName = "Imported",
                    isCustom = true,
                    dpiTweak = DpiTweakSettings(
                        sniHost = "cloudflare.com",
                        tcpFragmentMode = TcpFragmentMode.SPLIT_AT_SNI
                    )
                )
            }
        } catch (_: Exception) {
            // failed parsing
        }
        return null
    }

    fun exportConfigToJson(profile: TunnelProfile): String {
        val json = JSONObject()
        json.put("id", profile.id)
        json.put("name", profile.name)
        json.put("protocol", profile.protocol.name)
        json.put("serverHost", profile.serverHost)
        json.put("serverPort", profile.serverPort)
        json.put("uuidOrPassword", profile.uuidOrPassword)
        json.put("countryCode", profile.countryCode)
        json.put("cityName", profile.cityName)
        json.put("sniHost", profile.dpiTweak.sniHost)
        json.put("tcpFragmentMode", profile.dpiTweak.tcpFragmentMode.name)
        json.put("fakeHttpRequest", profile.dpiTweak.fakeHttpRequest)
        json.put("httpPayload", profile.dpiTweak.httpPayload)
        return json.toString(2)
    }
}
