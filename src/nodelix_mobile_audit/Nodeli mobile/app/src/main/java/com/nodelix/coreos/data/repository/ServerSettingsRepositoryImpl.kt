package com.nodelix.coreos.data.repository

import com.nodelix.coreos.core.AppError
import com.nodelix.coreos.core.AppResult
import com.nodelix.coreos.data.local.DiagnosticLogStore
import com.nodelix.coreos.data.model.SettingsSnapshotDto
import com.nodelix.coreos.data.model.UpdateAccountRequestDto
import com.nodelix.coreos.data.model.UpdateServerSettingsRequestDto
import com.nodelix.coreos.domain.repository.ServerSettingsRepository
import com.nodelix.coreos.network.ApiClient
import com.nodelix.coreos.network.NetworkResultMapper
import com.nodelix.coreos.security.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Server-side configuration repository (`settings.js`):
 * reads the full settings snapshot, applies partial per-group updates,
 * tests the Telegram bot integration, and changes the admin account.
 *
 * Secrets (API keys, tokens, passwords) are never logged; see [DiagnosticLogStore].
 */
class ServerSettingsRepositoryImpl(
    private val apiClient: ApiClient,
    private val sessionManager: SessionManager,
    private val logStore: DiagnosticLogStore,
    private val json: Json
) : ServerSettingsRepository {

    private val _snapshot = MutableStateFlow<SettingsSnapshotDto?>(null)
    override val snapshot: StateFlow<SettingsSnapshotDto?> = _snapshot.asStateFlow()

    override suspend fun refreshSettings(): AppResult<SettingsSnapshotDto> {
        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> return r
            is AppResult.Success -> r.data
        }
        return try {
            val resp = svc.getServerSettings()
            when (val mapped = NetworkResultMapper.mapResponse(resp, json) { sessionManager.onUnauthorizedOrExpired() }) {
                is AppResult.Failure -> mapped
                is AppResult.Success -> {
                    _snapshot.value = mapped.data
                    AppResult.Success(mapped.data)
                }
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "ServerSettings") {
                sessionManager.onUnauthorizedOrExpired()
            }
        }
    }

    override suspend fun updateSettingsGroup(group: String, fields: Map<String, String>): AppResult<String> {
        val normalized = group.trim().lowercase()
        if (normalized !in SUPPORTED_GROUPS) {
            return AppResult.Failure(AppError.BadRequest("Unknown settings group '$group'."))
        }
        val cleanFields = fields
            .mapKeys { it.key.trim() }
            .filter { it.key.isNotEmpty() }
        if (cleanFields.isEmpty()) {
            return AppResult.Failure(AppError.BadRequest("No settings fields provided."))
        }
        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> return r
            is AppResult.Success -> r.data
        }
        return try {
            val groupObject = JsonObject(cleanFields.mapValues { (_, v) -> v.toSettingPrimitive() })
            val body = when (normalized) {
                "esp32" -> UpdateServerSettingsRequestDto(esp32 = groupObject)
                "door" -> UpdateServerSettingsRequestDto(door = groupObject)
                "telegram" -> UpdateServerSettingsRequestDto(telegram = groupObject)
                "ai" -> UpdateServerSettingsRequestDto(ai = groupObject)
                "server" -> UpdateServerSettingsRequestDto(server = groupObject)
                else -> UpdateServerSettingsRequestDto(monitor = groupObject)
            }
            val resp = svc.updateServerSettings(body)
            when (val mapped = NetworkResultMapper.mapResponse(resp, json) { sessionManager.onUnauthorizedOrExpired() }) {
                is AppResult.Failure -> mapped
                is AppResult.Success -> {
                    logStore.info("ServerSettings", "Updated server settings group '$normalized'.")
                    refreshSettings()
                    AppResult.Success(mapped.data.resolvedMessage ?: "Server settings saved.")
                }
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "ServerSettings") {
                sessionManager.onUnauthorizedOrExpired()
            }
        }
    }

    override suspend fun testTelegramNotification(): AppResult<String> {
        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> return r
            is AppResult.Success -> r.data
        }
        return try {
            val resp = svc.testTelegramNotification()
            when (val mapped = NetworkResultMapper.mapResponse(resp, json) { sessionManager.onUnauthorizedOrExpired() }) {
                is AppResult.Failure -> mapped
                is AppResult.Success -> AppResult.Success(
                    mapped.data.resolvedMessage ?: "Telegram test message sent."
                )
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "Telegram") {
                sessionManager.onUnauthorizedOrExpired()
            }
        }
    }

    override suspend fun updateAdminAccount(
        currentPassword: String,
        newUsername: String?,
        newPassword: String?,
        confirmPassword: String?
    ): AppResult<String> {
        if (currentPassword.isBlank()) {
            return AppResult.Failure(AppError.BadRequest("Current admin password is required."))
        }
        val cleanUsername = newUsername?.trim()?.takeIf { it.isNotEmpty() }
        // Mirror backend `settings.js` username rule (`/^[^\s]{3,32}$/`) so invalid
        // names fail fast instead of tripping HTTP 400.
        if (cleanUsername != null && (cleanUsername.length !in 3..32 || cleanUsername.any { it.isWhitespace() })) {
            return AppResult.Failure(
                AppError.BadRequest("New username must be 3-32 characters with no whitespace.")
            )
        }
        val wantsPasswordChange = !newPassword.isNullOrEmpty() || !confirmPassword.isNullOrEmpty()
        if (cleanUsername == null && !wantsPasswordChange) {
            return AppResult.Failure(AppError.BadRequest("Nothing to change."))
        }
        if (wantsPasswordChange) {
            if ((newPassword ?: "").length < 8 || (newPassword ?: "").any { it == '\r' || it == '\n' }) {
                return AppResult.Failure(AppError.BadRequest("New password must be at least 8 characters."))
            }
            if (newPassword != confirmPassword) {
                return AppResult.Failure(AppError.BadRequest("New password confirmation does not match."))
            }
        }
        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> return r
            is AppResult.Success -> r.data
        }
        return try {
            val resp = svc.updateAccountSettings(
                UpdateAccountRequestDto(
                    password = currentPassword,
                    username = cleanUsername,
                    newPassword = newPassword?.takeIf { wantsPasswordChange },
                    confirmPassword = confirmPassword?.takeIf { wantsPasswordChange }
                )
            )
            when (val mapped = NetworkResultMapper.mapResponse(resp, json) { sessionManager.onUnauthorizedOrExpired() }) {
                is AppResult.Failure -> mapped
                is AppResult.Success -> {
                    logStore.info("ServerSettings", "Admin account updated on server.")
                    refreshSettings()
                    AppResult.Success(mapped.data.resolvedMessage ?: "Admin account updated.")
                }
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "ServerSettings") {
                sessionManager.onUnauthorizedOrExpired()
            }
        }
    }

    companion object {
        val SUPPORTED_GROUPS = setOf("esp32", "door", "telegram", "ai", "server", "monitor")

        /**
         * Converts a raw text field into a JSON primitive for `POST /api/settings`.
         *
         * Numeric/boolean coercion is only applied when it is loss-free: the parsed value
         * must render back to exactly the same text. Without that check a value such as an
         * API key `"0123456789"` (or a label that is all digits) would be sent as the JSON
         * number `123456789`, silently dropping the leading zero and corrupting the stored
         * device key. Everything else is sent as a string, which every backend
         * `FIELDS.set()` validator accepts (`vPort`/`vNum` parse, `vBool`/`vKey`/`vText`
         * stringify).
         */
        internal fun String.toSettingPrimitive(): JsonPrimitive {
            val t = this.trim()
            if (t.equals("true", ignoreCase = true)) return JsonPrimitive(true)
            if (t.equals("false", ignoreCase = true)) return JsonPrimitive(false)
            t.toLongOrNull()?.let { parsed -> if (parsed.toString() == t) return JsonPrimitive(parsed) }
            t.toDoubleOrNull()?.let { parsed -> if (parsed.toString() == t) return JsonPrimitive(parsed) }
            return JsonPrimitive(t)
        }
    }
}
