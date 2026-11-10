package com.example.tunnel.repository

import android.content.Context
import com.example.data.TunnelRepository
import com.example.model.TunnelProfile
import com.example.model.TunnelProtocol
import kotlinx.coroutines.flow.StateFlow

/**
 * Configuration repository handling connection profile validation,
 * secure credential storage, import/export, and persistence.
 */
class ConfigurationRepository(private val context: Context) {

    init {
        TunnelRepository.initialize(context)
    }

    val profiles: StateFlow<List<TunnelProfile>>
        get() = TunnelRepository.profiles

    val selectedProfile: StateFlow<TunnelProfile>
        get() = TunnelRepository.selectedProfile

    fun selectProfile(profile: TunnelProfile) {
        TunnelRepository.selectProfile(profile)
    }

    fun saveProfile(profile: TunnelProfile): Result<Unit> {
        val validation = validateProfile(profile)
        if (!validation.isValid) {
            return Result.failure(IllegalArgumentException(validation.errorMessage))
        }
        if (profiles.value.any { it.id == profile.id }) {
            TunnelRepository.updateProfile(profile)
        } else {
            TunnelRepository.addCustomProfile(profile)
        }
        return Result.success(Unit)
    }

    fun deleteProfile(id: String) {
        TunnelRepository.deleteProfile(id)
    }

    fun importConfig(rawText: String): Result<TunnelProfile> {
        val profile = TunnelRepository.importConfigFromString(rawText)
            ?: return Result.failure(IllegalArgumentException("Unsupported or invalid configuration format"))
        TunnelRepository.addCustomProfile(profile)
        return Result.success(profile)
    }

    fun exportConfig(profile: TunnelProfile): String {
        return TunnelRepository.exportConfigToJson(profile)
    }

    data class ProfileValidation(val isValid: Boolean, val errorMessage: String? = null)

    companion object {
        fun validateProfile(profile: TunnelProfile): ProfileValidation {
            if (profile.name.isBlank()) {
                return ProfileValidation(false, "Profile name cannot be blank")
            }
            if (profile.serverHost.isBlank()) {
                return ProfileValidation(false, "Server host/IP cannot be blank")
            }
            if (profile.serverPort !in 1..65535) {
                return ProfileValidation(false, "Server port must be between 1 and 65535")
            }
            return ProfileValidation(true)
        }
    }
}
