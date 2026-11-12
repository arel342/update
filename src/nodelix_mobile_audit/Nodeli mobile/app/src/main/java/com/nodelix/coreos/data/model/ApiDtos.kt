package com.nodelix.coreos.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ==================== GENERIC & LEGACY AUTH DTOs ====================

/**
 * Generic `{ success, message }` envelope returned by most NodelixcoreOS endpoints.
 *
 * Compatibility notes (verified against backend `main`):
 * - Legacy `/api/...` endpoints return `{ success: true|false, message?, error? }`.
 * - `/api/mobile/...` endpoints return BOTH `success` and `ok` plus a machine-readable
 *   `code` (e.g. `device_not_registered`, `challenge_expired`) and optional `retryAfter`.
 * - Always use [isOk] instead of reading a single raw flag.
 */
@Serializable
data class GenericStatusDto(
    val success: Boolean = false,
    val ok: Boolean = false,
    val code: String? = null,
    val message: String? = null,
    val error: String? = null,
    val setup: Boolean = false,
    val redirect: String? = null,
    val token: String? = null,
    val name: String? = null,
    val size: Long? = null,
    val retryAfter: Long? = null
) {
    /** True when either the legacy `success` flag or the mobile `ok` alias is set. */
    val isOk: Boolean get() = success || ok

    /** Best-effort human readable message from any server message field. */
    val resolvedMessage: String? get() = message ?: error
}

@Serializable
data class LoginRequestDto(
    val username: String,
    val password: String
)

@Serializable
data class SessionCheckDto(
    val authenticated: Boolean = false
)

@Serializable
data class LangDto(
    val lang: String = "id"
)

@Serializable
data class OtpVerifyRequestDto(
    val code: String
)

@Serializable
data class ResetPasswordRequestDto(
    val newPassword: String,
    val confirmPassword: String,
    val token: String
)

// ==================== MOBILE ECDSA AUTH CONTRACT DTOs (/api/mobile/...) ====================

@Serializable
data class MobileRegisterRequestDto(
    val username: String,
    val password: String,
    val deviceId: String,
    val deviceName: String,
    val androidVersion: String,
    val appVersion: String,
    val publicKeyPem: String
)

@Serializable
data class MobileRegisterResponseDto(
    val success: Boolean = false,
    val ok: Boolean = false,
    val status: String = "",
    val deviceId: String = "",
    val registeredAt: Long = 0L,
    /**
     * Standard session token field name. The backend also sends the legacy
     * [accessToken] alias — always read via [resolvedSessionToken].
     */
    val sessionToken: String? = null,
    val accessToken: String? = null,
    val tokenType: String = "Bearer",
    val expiresAt: Long? = null,
    val message: String? = null,
    val publicKeyFingerprint: String? = null,
    val algorithm: String? = null
) {
    val isOk: Boolean get() = success || ok
    val resolvedSessionToken: String? get() = sessionToken?.takeIf { it.isNotBlank() }
        ?: accessToken?.takeIf { it.isNotBlank() }
}

@Serializable
data class MobileChallengeRequestDto(
    val deviceId: String
)

@Serializable
data class MobileChallengeResponseDto(
    val success: Boolean = true,
    val ok: Boolean = true,
    val challengeId: String = "",
    val nonce: String = "", // Base64 32-byte random nonce
    val issuedAt: Long = 0L,
    val expiresAt: Long = 0L,
    val serverOrigin: String? = null,
    val originCandidates: Int? = null,
    val algorithm: String? = null,
    val ttlMs: Long? = null,
    val message: String? = null
)

@Serializable
data class MobileVerifyRequestDto(
    val deviceId: String,
    val challengeId: String,
    val timestamp: Long,
    val signatureBase64: String, // ASN.1 DER SHA256withECDSA signature
    /**
     * Optional origin hint (`scheme://host:port` as typed in the app) that the server
     * adds to its origin candidates. Improves verification when a reverse proxy changes the externally visible origin.
     */
    val serverOrigin: String? = null
)

@Serializable
data class MobileVerifyResponseDto(
    val success: Boolean = false,
    val ok: Boolean = false,
    /**
     * Standard session token field name (`sessionToken`); `accessToken` is the legacy
     * alias kept by the backend for compatibility. Always read via [resolvedSessionToken].
     */
    val sessionToken: String? = null,
    val accessToken: String? = null,
    val tokenType: String = "Bearer",
    val expiresAt: Long = 0L,
    val deviceId: String? = null,
    val username: String = "admin",
    val message: String? = null,
    /** True when this response came from `POST /api/mobile/auth/refresh` (rotated token). */
    val rotated: Boolean = false,
    val origin: String? = null
) {
    val isOk: Boolean get() = success || ok
    val resolvedSessionToken: String? get() = sessionToken?.takeIf { it.isNotBlank() }
        ?: accessToken?.takeIf { it.isNotBlank() }
}

@Serializable
data class MobileSessionDto(
    val success: Boolean = true,
    val authenticated: Boolean = false,
    val method: String? = null, // "mobile" | "web"
    val deviceId: String? = null,
    val deviceStatus: String? = null,
    val deviceName: String? = null,
    val publicKeyFingerprint: String? = null,
    val username: String? = null,
    val expiresAt: Long? = null,
    val message: String? = null
)

@Serializable
data class RegisteredDevicesResponseDto(
    val success: Boolean = true,
    val devices: List<RegisteredDeviceItemDto> = emptyList(),
    val count: Int? = null
)

@Serializable
data class RegisteredDeviceItemDto(
    val deviceId: String,
    val deviceName: String = "",
    val androidVersion: String = "",
    val appVersion: String = "",
    val publicKeyFingerprint: String = "",
    val algorithm: String? = null,
    val registeredAt: Long = 0L,
    val lastSeenAt: Long? = null,
    val status: String = "ACTIVE",
    val revokedAt: Long? = null,
    val sessions: Int? = null,
    val isCurrentDevice: Boolean? = null
)

// ==================== SYSTEM & SETTINGS DTOs ====================

@Serializable
data class SystemRamDto(
    val total: Long? = null,
    val used: Long? = null,
    val pct: Double? = null
)

@Serializable
data class SystemFlashDto(
    val ready: Boolean = false,
    val total: Long? = null,
    val free: Long? = null,
    val root: String? = null,
    val message: String? = null
)

@Serializable
data class SystemNowDto(
    val t: Long = 0L,
    val cpu: Double? = null,
    val ram: SystemRamDto? = null
)

@Serializable
data class SystemHistorySampleDto(
    val t: Long = 0L,
    val cpu: Double? = null,
    val ram: Double? = null
)

@Serializable
data class SystemResponseDto(
    val now: SystemNowDto? = null,
    val history: List<SystemHistorySampleDto> = emptyList()
)

@Serializable
data class SystemWsDataDto(
    val t: Long = 0L,
    val cpu: Double? = null,
    val ram: SystemRamDto? = null,
    val flash: SystemFlashDto? = null,
    val cores: Int? = null,
    val model: String? = null,
    val load: Double? = null,
    val uptime: Long? = null,
    val unavailable: List<String> = emptyList()
)

@Serializable
data class SettingsInfoDto(
    val port: Int = 3000,
    val ips: List<String> = emptyList(),
    val lang: String = "id",
    val version: String = "",
    val node: String = "",
    val setupDone: Boolean = true,
    val setupRequired: Boolean = false,
    val username: String = "admin",
    val uptime: Long = 0L
)

/**
 * Full `GET /api/settings` group contracts (verified against backend `settings.js`
 * `FIELDS` groups: `esp32`, `door`, `telegram`, `ai`, `server`, `monitor` + `info`).
 */
@Serializable
data class Esp32SettingsGroupDto(
    val ip: String = "",
    val port: Int = 80,
    val apiKey: String = "",
    val pollMs: Int = 1000,
    val labels: String = ""
)

@Serializable
data class DoorSettingsGroupDto(
    val ip: String = "",
    val port: Int = 80,
    val apiKey: String = "",
    val enrollSec: Int = 30,
    val pollMs: Int = 1000,
    val notifyOpen: Boolean = false,
    val notifyUnknown: Boolean = false
)

@Serializable
data class TelegramSettingsGroupDto(
    val alerts: Boolean = true,
    val token: String = "",
    val chatId: String = ""
)

@Serializable
data class AiSettingsGroupDto(
    val enabled: Boolean = true,
    val apiKey: String = "",
    val model: String = ""
)

@Serializable
data class ServerSettingsGroupDto(
    val port: Int = 3000,
    val sessionHours: Int = 24,
    val storageFolder: String = "upload",
    val otaMaxMB: Int = 4,
    val otaTimeoutS: Int = 240,
    val otaWaitS: Int = 60
)

@Serializable
data class MonitorSettingsGroupDto(
    val iWarn: Double = 5.0,
    val cellVMax: Double = 4.25,
    val cellVCrit: Double = 3.2,
    val cellVLow: Double = 3.4,
    val cellDeltaWarn: Double = 0.1,
    val iDeadband: Double = 0.05,
    val acVoltage: Double = 220.0,
    val acPowerFactor: Double = 0.8
)

@Serializable
data class SettingsSnapshotDto(
    val esp32: Esp32SettingsGroupDto? = null,
    val door: DoorSettingsGroupDto? = null,
    val telegram: TelegramSettingsGroupDto? = null,
    val ai: AiSettingsGroupDto? = null,
    val server: ServerSettingsGroupDto? = null,
    val monitor: MonitorSettingsGroupDto? = null,
    val info: SettingsInfoDto? = null
)

/** Partial settings update body: `{ "<group>": { "<field>": <value> }, ... }` (unknown fields ignored). */
@Serializable
data class UpdateServerSettingsRequestDto(
    val esp32: kotlinx.serialization.json.JsonObject? = null,
    val door: kotlinx.serialization.json.JsonObject? = null,
    val telegram: kotlinx.serialization.json.JsonObject? = null,
    val ai: kotlinx.serialization.json.JsonObject? = null,
    val server: kotlinx.serialization.json.JsonObject? = null,
    val monitor: kotlinx.serialization.json.JsonObject? = null
)

/**
 * Admin account change (`POST /api/settings/account`).
 * `password` is the CURRENT dashboard password (always required, rate-limited server-side);
 * other sessions are revoked when the password changes.
 */
@Serializable
data class UpdateAccountRequestDto(
    val password: String,
    val username: String? = null,
    val newPassword: String? = null,
    val confirmPassword: String? = null
)

// ==================== ESP32 & RELAY DTOs ====================

@Serializable
data class EspIdentityDto(
    val ip: String? = null,
    val rssi: Int? = null,
    val uptime: Long? = null,
    val fw: String? = null,
    val heap: Long? = null
)

@Serializable
data class BatteryDto(
    val mode: String = "pack",
    val pack: Double = 0.0,
    val cells: List<Double> = emptyList(),
    val socs: List<Double> = emptyList(),
    val soc: Double = 0.0,
    val min: Double = 0.0,
    val max: Double = 0.0,
    val delta: Double = 0.0,
    val status: String = "",
    val statusKey: String = "normal",
    val level: String = "ok"
)

@Serializable
data class CurrentDto(
    val a: Double = 0.0,
    val peak: Double = 0.0,
    val clip: Boolean = false,
    val va: Double = 0.0
)

@Serializable
data class DhtDto(
    val ok: Boolean = false,
    val t: Double? = null,
    val h: Double? = null,
    val pin: Int? = null,
    val age: Long? = null,
    val err: String? = null
)

@Serializable
data class AuxDto(
    val relay: Int = 1,
    @SerialName("on_pin") val onPin: Int = 23,
    @SerialName("off_pin") val offPin: Int = 22,
    val on: Int = 0,
    val off: Int = 1
)

@Serializable
data class RelayDto(
    val id: Int = 1,
    val ch: Int = 1,
    val pin: Int? = null,
    val on: Boolean = false,
    val name: String = ""
)

@Serializable
data class EspAlertDto(
    val key: String = "",
    val k: String = "",
    val level: String = "warn"
)

@Serializable
data class EspLimitsDto(
    val iWarn: Double? = null
)

@Serializable
data class EspHistoryItemDto(
    val t: Long = 0L,
    val v: Double? = null,
    val i: Double? = null,
    val tc: Double? = null,
    val h: Double? = null
)

@Serializable
data class Esp32StatusDto(
    val online: Boolean = false,
    val lastOk: Long = 0L,
    val fails: Int = 0,
    val lastError: String = "",
    val lastErrorAt: Long = 0L,
    val updating: Boolean = false,
    val esp: EspIdentityDto? = null,
    val battery: BatteryDto? = null,
    val current: CurrentDto? = null,
    val dht: DhtDto? = null,
    val aux: AuxDto? = null,
    val relays: List<RelayDto> = emptyList(),
    val alerts: List<EspAlertDto> = emptyList(),
    val zeroing: Boolean = false,
    val limits: EspLimitsDto? = null,
    val history: List<EspHistoryItemDto> = emptyList()
)

@Serializable
data class RelayCommandRequestDto(
    val id: String,
    val state: Boolean
)

@Serializable
data class RelayCommandResponseDto(
    val success: Boolean = false,
    val message: String? = null,
    val relays: List<RelayDto> = emptyList(),
    val aux: AuxDto? = null
)

@Serializable
data class CalibrateRequestDto(
    val type: String,
    val id: String? = null,
    val actual: Double? = null
)

// ==================== DOOR LOCK (ESP8266) DTOs ====================

@Serializable
data class DoorEnrollDto(
    val active: Boolean = false,
    val left: Int = 0,
    val total: Int = 30
)

@Serializable
data class DoorCardDto(
    val uid: String = "",
    val name: String = "",
    val admin: Boolean = false,
    val added: Long? = null,
    val last: Long? = null
)

@Serializable
data class DoorLogDto(
    val t: Long = 0L,
    val type: String = "info",
    val k: String = "",
    val text: String = ""
)

@Serializable
data class DoorStatusDto(
    val configured: Boolean = false,
    val online: Boolean = false,
    val lastOk: Long = 0L,
    val fails: Int = 0,
    val lastError: String = "",
    val updating: Boolean = false,
    val esp: EspIdentityDto? = null,
    val open: Boolean = false,
    val left: Int = 0,
    val enabled: Boolean = true,
    val openSec: Int = 5,
    val synced: Boolean = false,
    val enroll: DoorEnrollDto = DoorEnrollDto(),
    val cards: List<DoorCardDto> = emptyList(),
    val log: List<DoorLogDto> = emptyList()
)

@Serializable
data class DoorLockToggleRequestDto(val enabled: Boolean)

@Serializable
data class DoorConfigRequestDto(val openSec: Int)

@Serializable
data class DoorEnrollRequestDto(val action: String) // "start" | "cancel"

@Serializable
data class DoorCardRenameRequestDto(val uid: String, val name: String)

@Serializable
data class DoorCardAdminRequestDto(
    val uid: String,
    val admin: Boolean,
    val password: String? = null
)

@Serializable
data class DeviceTestRequestDto(val device: String)

@Serializable
data class DeviceTestResponseDto(
    val success: Boolean = false,
    val message: String? = null,
    val device: String? = null,
    val fw: String? = null,
    val ip: String? = null,
    val rssi: Int? = null
)

// ==================== STORAGE DTOs ====================

@Serializable
data class StorageStatusDto(
    val mode: String = "",
    val root: String = "",
    val ready: Boolean = false,
    val message: String? = null,
    val total: Long? = null,
    val free: Long? = null
)

@Serializable
data class FileItemDto(
    val name: String,
    val isDirectory: Boolean = false,
    val size: Long = 0L,
    val modified: String? = null
)

@Serializable
data class FileListResponseDto(
    val currentDir: String = "",
    val files: List<FileItemDto> = emptyList()
)

@Serializable
data class CreateFolderRequestDto(
    val dir: String,
    val folderName: String
)

@Serializable
data class RenameFileRequestDto(
    val dir: String,
    val oldName: String,
    val newName: String
)

@Serializable
data class CompressFileRequestDto(
    val dir: String,
    val name: String
)

/** Copy/cut entries between server directories (`POST /api/paste`, verified in `server.js`). */
@Serializable
data class PasteRequestDto(
    val action: String, // "copy" | "cut"
    val sourceDir: String,
    val targetDir: String,
    val items: List<String>
)

// ==================== FIRMWARE & UPDATE CENTER DTOs ====================

@Serializable
data class FirmwareDeviceItemDto(
    val key: String = "",
    val label: String = "",
    val host: String = "",
    val port: Int = 80,
    val configured: Boolean = false,
    val online: Boolean = false,
    val fw: String = "",
    val ip: String = "",
    val rssi: Int? = null,
    val uptime: Long? = null,
    val updating: Boolean = false
)

@Serializable
data class FirmwareLastResultDto(
    val at: Long = 0L,
    val device: String = "",
    val ok: Boolean = false,
    val fw: String? = null,
    val ms: Long? = null,
    val size: Long? = null,
    val error: String? = null
)

@Serializable
data class FirmwareStatusResponseDto(
    val devices: List<FirmwareDeviceItemDto> = emptyList(),
    val maxMB: Int = 4,
    val busy: Boolean = false,
    val busyDevice: String = "",
    val last: FirmwareLastResultDto? = null
)

@Serializable
data class FirmwareFlashResponseDto(
    val success: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val device: String? = null,
    val fw: String? = null,
    val ms: Long? = null,
    val warning: String? = null
)

@Serializable
data class UpdaterCommitDto(
    val hash: String = "",
    val short: String = "",
    val subject: String = "",
    val author: String = "",
    val date: String = ""
)

@Serializable
data class UpdaterStatusDto(
    val success: Boolean = true,
    val message: String? = null,
    val enabled: Boolean = true,
    val repo: String = "arel342/NodelixcoreOS",
    val branch: String = "main",
    val installedCommit: String = "",
    val installedShort: String = "",
    val latestCommit: String = "",
    val latestShort: String = "",
    val updateAvailable: Boolean = false,
    val detectedAt: Long? = null,
    val lastCheckedAt: Long? = null,
    val lastCheckError: String = "",
    val lastCheckErrorDetail: String = "",
    val commits: List<UpdaterCommitDto> = emptyList(),
    val notifyStatus: String = "none"
)

// ==================== WEBSOCKET ENVELOPE DTO ====================

@Serializable
data class WebSocketEnvelopeDto(
    val type: String = "",
    val count: Int? = null,
    val k: String? = null,
    val level: String? = null,
    val text: String? = null,
    val data: JsonElement? = null
)

@Serializable
data class FirmwareWsEventDto(
    val device: String = "",
    val phase: String = "",
    val message: String = "",
    val fw: String? = null,
    val at: Long = 0L
)
