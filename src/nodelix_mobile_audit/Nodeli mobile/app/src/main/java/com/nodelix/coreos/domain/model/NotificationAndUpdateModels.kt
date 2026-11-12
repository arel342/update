package com.nodelix.coreos.domain.model

enum class NotificationCategory(val label: String) {
    SERVER("Server"),
    SYSTEM("System"),
    ESP32("ESP32"),
    ESP8266("ESP8266"),
    DOOR_LOCK("Door Lock"),
    RELAY("Relay"),
    STORAGE("Storage"),
    FIRMWARE("Firmware"),
    UPDATE("Update"),
    SECURITY("Security")
}

data class AppNotification(
    val id: String,
    val title: String,
    val description: String,
    val timestampMillis: Long,
    val category: NotificationCategory,
    val severity: LogSeverity = LogSeverity.INFO,
    val isRead: Boolean = false
)

data class GitCommitSummary(
    val hash: String,
    val shortHash: String,
    val subject: String,
    val author: String,
    val isoDate: String
)

data class ServerUpdateState(
    val enabled: Boolean,
    val repo: String,
    val branch: String,
    val installedCommit: String,
    val installedShort: String,
    val latestCommit: String,
    val latestShort: String,
    val updateAvailable: Boolean,
    val detectedAtMillis: Long?,
    val lastCheckedAtMillis: Long?,
    val lastCheckError: String,
    val lastCheckErrorDetail: String,
    val commits: List<GitCommitSummary>,
    val notifyStatus: String
)

data class FirmwareDeviceState(
    val key: String, // "esp32" | "esp8266"
    val label: String,
    val host: String,
    val port: Int,
    val configured: Boolean,
    val online: Boolean,
    val currentFirmware: String,
    val rssiDbm: Int?,
    val uptimeSeconds: Long?,
    val updating: Boolean
)

data class FirmwareFlashProgress(
    val device: String,
    val phase: String, // "idle" | "send" | "reboot" | "done" | "error"
    val message: String,
    val timestampMillis: Long,
    val newFirmwareVersion: String? = null
)

/** Last completed OTA flash attempt reported by `GET /api/firmware` (`last`). */
data class LastFirmwareFlash(
    val timestampMillis: Long,
    val device: String,
    val success: Boolean,
    val firmwareVersion: String?,
    val durationMs: Long?,
    val sizeBytes: Long?,
    val error: String?
)

data class AndroidAppUpdateState(
    val currentVersion: String,
    val latestVersion: String?,
    val updateAvailable: Boolean,
    val releaseNotes: String?,
    val releaseHtmlUrl: String?,
    val assetSizeBytes: Long?,
    val checkedAtMillis: Long?,
    val statusMessage: String?
)

enum class LogSeverity {
    DEBUG,
    INFO,
    WARN,
    ERROR
}

data class DiagnosticLogEntry(
    val id: Long,
    val timestampMillis: Long,
    val severity: LogSeverity,
    val tag: String,
    val sanitizedMessage: String
)

data class DiagnosticSummary(
    val serverName: String?,
    val serverEndpoint: String?,
    val connectionState: ConnectionState,
    val apiResponseTimeMs: Long?,
    val webSocketConnected: Boolean,
    val lastSyncMillis: Long?,
    val lastError: String?,
    val androidNetworkOnline: Boolean,
    val androidWifiConnected: Boolean,
    val localIpv4Address: String?,
    val appVersion: String,
    val backendVersion: String?,
    val nodeVersion: String?,
    val authMode: AuthMode?,
    val esp32Online: Boolean?,
    val doorOnline: Boolean?
)
