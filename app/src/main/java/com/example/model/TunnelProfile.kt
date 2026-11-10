package com.example.model

import java.util.UUID

data class TunnelProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: TunnelProtocol,
    val serverHost: String,
    val serverPort: Int = protocol.defaultPort,
    val uuidOrPassword: String = "",
    val countryCode: String = "US",
    val cityName: String = "Global",
    val pingMs: Int = -1,
    val dpiTweak: DpiTweakSettings = DpiTweakSettings(),
    val isCustom: Boolean = false,
    val isFavorite: Boolean = false
) {
    val countryFlag: String
        get() = countryCodeToFlagEmoji(countryCode)

    companion object {
        fun countryCodeToFlagEmoji(countryCode: String): String {
            if (countryCode.length != 2) return "🌐"
            val firstChar = Character.codePointAt(countryCode.uppercase(), 0) - 0x41 + 0x1F1E6
            val secondChar = Character.codePointAt(countryCode.uppercase(), 1) - 0x41 + 0x1F1E6
            return String(Character.toChars(firstChar)) + String(Character.toChars(secondChar))
        }
    }
}
