package com.nodelix.coreos.core

sealed interface AppError {
    val userMessage: String

    data class Offline(override val userMessage: String = "Network or server is offline.") : AppError
    data class ConnectionRefused(override val userMessage: String = "Server refused the connection. Verify IP and port.") : AppError
    data class InvalidEndpoint(override val userMessage: String) : AppError
    data class Timeout(override val userMessage: String = "Request timed out before the server responded.") : AppError
    data class LanHttpBlocked(override val userMessage: String = "HTTP is limited to private LAN hosts.") : AppError
    data class SetupRequired(override val userMessage: String = "Server initial setup is not finished yet (/setup.html).") : AppError
    data class AuthRequired(override val userMessage: String = "Session expired or unauthenticated. Please sign in again.") : AppError
    data class Forbidden(override val userMessage: String = "Access denied by server policy.") : AppError
    data class NotFound(override val userMessage: String = "Requested endpoint or resource was not found on server.") : AppError
    data class RateLimited(override val userMessage: String = "Too many attempts. Please wait before trying again.") : AppError
    data class DeviceRevoked(override val userMessage: String = "This Android device has been revoked on the server.") : AppError
    data class DeviceOffline(override val userMessage: String = "Target microcontroller (ESP32/ESP8266) is offline.") : AppError
    data class BiometricUnavailable(override val userMessage: String = "Biometric hardware or enrolled fingerprint is unavailable.") : AppError
    data class BiometricLockout(override val userMessage: String = "Biometric sensor is temporarily locked due to failed attempts. Use Admin Password.") : AppError
    data class KeyInvalidated(override val userMessage: String = "Biometric enrollment changed on this phone. Re-register device security key.") : AppError
    data class InvalidSignature(override val userMessage: String = "Cryptographic challenge signature verification failed.") : AppError
    data class MalformedPayload(override val userMessage: String = "Server returned an unexpected or malformed data payload.") : AppError
    data class StorageUnavailable(override val userMessage: String = "Server external storage / flash drive is not ready.") : AppError
    data class BadRequest(override val userMessage: String) : AppError
    data class ServerError(override val userMessage: String = "Internal server error occurred.") : AppError
    data class Unknown(override val userMessage: String = "An unexpected communication error occurred.") : AppError
}

sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>

    fun <R> map(transform: (T) -> R): AppResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> this
    }
}

/**
 * Standard screen UI state covering Part 21 requirements:
 * Loading, Success, Empty, Error, Offline, AuthenticationRequired.
 */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Empty(val message: String = "No data available from server.") : UiState<Nothing>
    data class Error(val error: AppError) : UiState<Nothing>
    data class Offline(val message: String = "Server unreachable on Wi-Fi LAN.", val cachedSummary: String? = null) : UiState<Nothing>
    data class AuthenticationRequired(val reason: String = "Authentication required to continue.") : UiState<Nothing>
}
