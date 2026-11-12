package com.nodelix.coreos.domain.repository

import android.net.Uri
import com.nodelix.coreos.core.AppResult
import com.nodelix.coreos.domain.model.AndroidAppUpdateState
import com.nodelix.coreos.domain.model.AppNotification
import com.nodelix.coreos.domain.model.ConnectionProbeResult
import com.nodelix.coreos.domain.model.ConnectionState
import com.nodelix.coreos.domain.model.DiagnosticLogEntry
import com.nodelix.coreos.domain.model.DiagnosticSummary
import com.nodelix.coreos.domain.model.DoorState
import com.nodelix.coreos.domain.model.Esp32State
import com.nodelix.coreos.domain.model.FirmwareDeviceState
import com.nodelix.coreos.domain.model.FirmwareFlashProgress
import com.nodelix.coreos.domain.model.LastFirmwareFlash
import com.nodelix.coreos.domain.model.NotificationCategory
import com.nodelix.coreos.domain.model.RegisteredMobileDevice
import com.nodelix.coreos.domain.model.ServerProfile
import com.nodelix.coreos.domain.model.ServerUpdateState
import com.nodelix.coreos.domain.model.SessionState
import com.nodelix.coreos.domain.model.StorageDirectoryListing
import com.nodelix.coreos.domain.model.SystemOverview
import com.nodelix.coreos.domain.model.TextFilePreview
import com.nodelix.coreos.domain.model.TransferProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.security.Signature

interface ServerProfileRepository {
    val profiles: Flow<List<ServerProfile>>
    val activeProfile: StateFlow<ServerProfile?>
    val connectionState: StateFlow<ConnectionState>

    suspend fun saveProfile(profile: ServerProfile): AppResult<ServerProfile>
    suspend fun deleteProfile(profileId: String): AppResult<Unit>
    suspend fun setActiveProfile(profileId: String): AppResult<ServerProfile>
    suspend fun testConnection(profile: ServerProfile): AppResult<ConnectionProbeResult>
    suspend fun connectActiveServer(): AppResult<ConnectionProbeResult>
    suspend fun disconnectActiveServer()
}

data class PendingChallenge(
    val challengeId: String,
    val nonceBase64: String,
    val serverOrigin: String,
    val deviceId: String,
    val timestampMillis: Long,
    val expiresAtMillis: Long,
    val signatureObject: Signature,
    val canonicalPayload: ByteArray,
    val isLegacyLocalUnlock: Boolean
)

interface SecurityAuthRepository {
    val sessionState: StateFlow<SessionState>

    suspend fun loginAdminAndRegisterDevice(
        username: String,
        password: String,
        deviceName: String,
        enableBiometricKey: Boolean
    ): AppResult<SessionState>

    suspend fun prepareBiometricChallenge(): AppResult<PendingChallenge>

    suspend fun completeBiometricChallenge(
        challenge: PendingChallenge,
        signedSignature: Signature
    ): AppResult<SessionState>

    suspend fun verifyActiveSession(): AppResult<Boolean>

    /**
     * Rotates the active Bearer session via `POST /api/mobile/auth/refresh`.
     * Only valid in [com.nodelix.coreos.domain.model.AuthMode.MOBILE_ECDSA_BEARER] mode.
     */
    suspend fun refreshBearerSession(): AppResult<com.nodelix.coreos.domain.model.SessionState>

    suspend fun logout(): AppResult<Unit>

    // Recovery via Telegram OTP (supported natively by NodelixcoreOS server.js)
    suspend fun requestPasswordResetOtp(): AppResult<String>
    suspend fun verifyPasswordResetOtp(code: String): AppResult<String>
    suspend fun resetAdminPassword(token: String, newPassword: String, confirmPassword: String): AppResult<String>

    // Registered mobile device security management
    suspend fun getRegisteredDevices(): AppResult<List<RegisteredMobileDevice>>
    suspend fun revokeRegisteredDevice(deviceId: String): AppResult<Unit>
}

interface SystemMonitorRepository {
    val systemOverview: StateFlow<SystemOverview?>
    suspend fun refreshSystemOverview(): AppResult<SystemOverview>
}

interface DeviceControlRepository {
    val esp32State: StateFlow<Esp32State?>
    val doorState: StateFlow<DoorState?>

    suspend fun refreshAllDevices(): AppResult<Pair<Esp32State, DoorState>>
    suspend fun refreshEsp32(): AppResult<Esp32State>
    suspend fun refreshDoor(): AppResult<DoorState>
    suspend fun testDeviceConnection(deviceKey: String): AppResult<String>

    // ESP32 controls
    suspend fun setRelayState(relayId: String, turnOn: Boolean): AppResult<Esp32State>
    suspend fun calibrateEsp32(type: String, tapId: String? = null, actualValue: Double? = null): AppResult<String>

    // ESP8266 Door Lock controls (strictly non-retried on timeout)
    suspend fun openDoor(): AppResult<String>
    suspend fun closeDoor(): AppResult<String>
    suspend fun setDoorRfidEnabled(enabled: Boolean): AppResult<String>
    suspend fun setDoorOpenDuration(seconds: Int): AppResult<String>
    suspend fun setDoorEnrollMode(start: Boolean): AppResult<String>
    suspend fun renameRfidCard(uid: String, newName: String): AppResult<Unit>
    suspend fun setRfidCardAdmin(uid: String, isAdmin: Boolean, adminPassword: String?): AppResult<String>
    suspend fun deleteRfidCard(uid: String): AppResult<Unit>
}

interface StorageManagerRepository {
    val transferProgress: StateFlow<TransferProgress?>

    suspend fun listDirectory(relativeDir: String): AppResult<StorageDirectoryListing>
    suspend fun createFolder(parentDir: String, folderName: String): AppResult<Unit>
    suspend fun renameEntry(parentDir: String, oldName: String, newName: String): AppResult<Unit>
    suspend fun deleteEntry(relativePath: String): AppResult<Unit>
    suspend fun compressToZip(parentDir: String, entryName: String): AppResult<String>

    /**
     * Copies or moves entries between server directories (`POST /api/paste`).
     * @param action "copy" or "cut".
     */
    suspend fun pasteEntries(
        action: String,
        sourceDir: String,
        targetDir: String,
        items: List<String>
    ): AppResult<String>
    suspend fun uploadFile(targetDir: String, sourceUri: Uri, fileName: String, sizeBytes: Long): AppResult<String>
    suspend fun downloadFile(relativePath: String, destinationUri: Uri, asZip: Boolean = false): AppResult<Long>
    suspend fun readTextPreview(relativePath: String): AppResult<TextFilePreview>
    fun cancelActiveTransfer()
    fun buildAuthenticatedMediaUrl(relativePath: String): String?
}

interface NotificationCenterRepository {
    val notifications: StateFlow<List<AppNotification>>
    val unreadCount: StateFlow<Int>

    fun pushNotification(
        title: String,
        description: String,
        category: NotificationCategory,
        dedupKey: String? = null
    )
    fun recordLocalNotification(notification: AppNotification)
    suspend fun markAsRead(notificationId: String)
    suspend fun markAllAsRead()
    suspend fun clearNotifications(category: NotificationCategory? = null)
}

interface UpdateCenterRepository {
    val serverUpdateState: StateFlow<ServerUpdateState?>
    val firmwareDevices: StateFlow<List<FirmwareDeviceState>>
    val firmwareFlashProgress: StateFlow<FirmwareFlashProgress?>
    val lastFirmwareFlash: StateFlow<LastFirmwareFlash?>
    val androidUpdateState: StateFlow<AndroidAppUpdateState>

    suspend fun refreshUpdateCenter(): AppResult<Unit>
    suspend fun checkServerUpdateManual(): AppResult<ServerUpdateState>

    /** Re-sends the pending server-update notification to the admin Telegram chat. */
    suspend fun resendUpdateNotification(): AppResult<ServerUpdateState>

    suspend fun flashEspFirmware(deviceKey: String, firmwareUri: Uri, fileName: String): AppResult<String>
    suspend fun checkAndroidAppUpdate(): AppResult<AndroidAppUpdateState>
}

interface ServerSettingsRepository {
    val snapshot: StateFlow<com.nodelix.coreos.data.model.SettingsSnapshotDto?>

    suspend fun refreshSettings(): AppResult<com.nodelix.coreos.data.model.SettingsSnapshotDto>

    /**
     * Updates one server settings group (`esp32`, `door`, `telegram`, `ai`, `server`,
     * `monitor`). Values are parsed as boolean/long/double when possible, else string.
     */
    suspend fun updateSettingsGroup(group: String, fields: Map<String, String>): AppResult<String>

    /** Sends a Telegram test message through the server-configured bot. */
    suspend fun testTelegramNotification(): AppResult<String>

    /**
     * Changes the admin account. Requires the CURRENT dashboard password; other web
     * sessions are revoked when the password changes.
     */
    suspend fun updateAdminAccount(
        currentPassword: String,
        newUsername: String?,
        newPassword: String?,
        confirmPassword: String?
    ): AppResult<String>
}

interface DiagnosticsRepository {
    val logs: StateFlow<List<DiagnosticLogEntry>>
    suspend fun buildDiagnosticSummary(): DiagnosticSummary
    suspend fun formatSanitizedReport(): String
    fun clearLogs()
}
