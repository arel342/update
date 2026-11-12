package com.nodelix.coreos.domain.usecase

import com.nodelix.coreos.core.AppError
import com.nodelix.coreos.core.AppResult
import com.nodelix.coreos.domain.model.ConnectionProbeResult
import com.nodelix.coreos.domain.model.Esp32State
import com.nodelix.coreos.domain.model.ServerProfile
import com.nodelix.coreos.domain.repository.DeviceControlRepository
import com.nodelix.coreos.domain.repository.ServerProfileRepository
import com.nodelix.coreos.util.ValidationOutcome
import com.nodelix.coreos.util.Validators

class ValidateAndSaveServerUseCase(
    private val serverRepository: ServerProfileRepository
) {
    suspend operator fun invoke(profile: ServerProfile): AppResult<ServerProfile> {
        if (profile.name.isBlank()) {
            return AppResult.Failure(AppError.InvalidEndpoint("Server name cannot be empty."))
        }
        val urlCheck = Validators.validateBaseUrl(profile.protocol.scheme, profile.host, profile.port)
        if (urlCheck is ValidationOutcome.Invalid) {
            return AppResult.Failure(AppError.InvalidEndpoint(urlCheck.reason))
        }
        if (!Validators.isValidTimeoutSeconds(profile.timeoutSeconds)) {
            return AppResult.Failure(
                AppError.InvalidEndpoint("Timeout must be between 3 and 120 seconds.")
            )
        }
        if (!Validators.isPrivateLanHost(profile.host)) {
            return AppResult.Failure(
                AppError.LanHttpBlocked("HTTP-only mode permits private/LAN hosts only.")
            )
        }
        return serverRepository.saveProfile(profile.copy(host = profile.host.trim(), name = profile.name.trim()))
    }
}

class ConnectServerUseCase(
    private val serverRepository: ServerProfileRepository
) {
    suspend operator fun invoke(profileId: String): AppResult<ConnectionProbeResult> {
        return when (val setRes = serverRepository.setActiveProfile(profileId)) {
            is AppResult.Failure -> setRes
            is AppResult.Success -> serverRepository.connectActiveServer()
        }
    }
}

/**
 * Enforces Part 9.3 safety rules for Door Lock actuation:
 * - Requires explicit user confirmation in caller UI (`userConfirmed == true`).
 * - NEVER retries automatically on Timeout because physical door state is indeterminate.
 */
class ControlDoorUseCase(
    private val deviceRepository: DeviceControlRepository
) {
    enum class Action { OPEN, CLOSE }

    suspend operator fun invoke(action: Action, userConfirmed: Boolean): AppResult<String> {
        if (!userConfirmed) {
            return AppResult.Failure(
                AppError.BadRequest("Door lock control requires explicit operator confirmation.")
            )
        }
        val result = when (action) {
            Action.OPEN -> deviceRepository.openDoor()
            Action.CLOSE -> deviceRepository.closeDoor()
        }
        return when (result) {
            is AppResult.Failure -> {
                if (result.error is AppError.Timeout && action == Action.OPEN) {
                    AppResult.Failure(
                        AppError.Timeout(
                            "Door open command timed out. Automatic retry was blocked for safety because the door may already be unlocked. Tap REFRESH STATUS first."
                        )
                    )
                } else {
                    result
                }
            }
            is AppResult.Success -> result
        }
    }
}

class ControlRelayUseCase(
    private val deviceRepository: DeviceControlRepository
) {
    suspend operator fun invoke(
        relayId: String,
        targetState: Boolean,
        userConfirmed: Boolean
    ): AppResult<Esp32State> {
        if (!userConfirmed) {
            return AppResult.Failure(
                AppError.BadRequest("Relay actuation requires confirmation.")
            )
        }
        // Client-side channel validation mirroring backend `esp32.js resolveChannel()`
        // so unknown channels (e.g. "99") fail fast instead of tripping HTTP 400.
        val normalized = Validators.normalizeRelayId(relayId)
            ?: return AppResult.Failure(
                AppError.BadRequest(
                    "Unknown relay channel '$relayId'. Use 1 (Relay 1 & 2), 3, 4, or all."
                )
            )
        return deviceRepository.setRelayState(normalized, targetState)
    }
}
