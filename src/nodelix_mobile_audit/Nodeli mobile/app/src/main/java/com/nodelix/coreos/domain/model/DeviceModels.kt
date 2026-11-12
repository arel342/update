package com.nodelix.coreos.domain.model

enum class DeviceType {
    ESP32,
    ESP8266
}

data class BatteryMetrics(
    val mode: String, // "cells" | "pack"
    val packVoltage: Double,
    val cellsVoltage: List<Double>,
    val cellsSoc: List<Double>,
    val averageSocPercent: Double,
    val minCellVoltage: Double,
    val maxCellVoltage: Double,
    val cellDeltaVoltage: Double,
    val statusText: String,
    val statusKey: String,
    val level: String // "ok" | "warn" | "bad"
)

data class CurrentMetrics(
    val amperes: Double,
    val peakAmperes: Double,
    val clipping: Boolean,
    val apparentPowerVa: Double
)

data class DhtMetrics(
    val ok: Boolean,
    val temperatureCelsius: Double?,
    val humidityPercent: Double?,
    val pin: Int?,
    val ageMs: Long?,
    val error: String?
)

data class RelayChannel(
    val id: Int,          // 1 ("Relay 1 & 2"), 3 ("Relay 3"), 4 ("Relay 4")
    val channel: Int,     // HTTP channel 1..3
    val pin: Int?,        // GPIO 26, 27, 32
    val name: String,
    val isOn: Boolean
)

data class AuxOutputState(
    val relayId: Int,
    val onPin: Int,
    val offPin: Int,
    val onHigh: Boolean,
    val offHigh: Boolean
)

data class Esp32HistorySample(
    val timestampMillis: Long,
    val packVoltage: Double?,
    val currentAmps: Double?,
    val temperatureCelsius: Double?,
    val humidityPercent: Double?
)

data class Esp32Alert(
    val key: String,
    val code: String,
    val level: String
)

data class Esp32State(
    val configured: Boolean,
    val online: Boolean,
    val updatingFirmware: Boolean,
    val lastOkMillis: Long?,
    val consecutiveFailures: Int,
    val lastError: String?,
    val ipAddress: String?,
    val rssiDbm: Int?,
    val uptimeSeconds: Long?,
    val firmwareVersion: String?,
    val freeHeapBytes: Long?,
    val battery: BatteryMetrics?,
    val current: CurrentMetrics?,
    val dht: DhtMetrics?,
    val aux: AuxOutputState?,
    val relays: List<RelayChannel>,
    val alerts: List<Esp32Alert>,
    val zeroingActive: Boolean,
    val currentWarnLimitAmps: Double?,
    val history: List<Esp32HistorySample>
)

data class RfidCard(
    val uid: String,
    val name: String,
    val isAdmin: Boolean,
    val addedAtMillis: Long?,
    val lastUsedMillis: Long?
)

data class DoorEventEntry(
    val timestampMillis: Long,
    val type: String, // "ok" | "warn" | "bad" | "info" | "remote"
    val key: String,
    val text: String
)

data class EnrollState(
    val active: Boolean,
    val secondsLeft: Int,
    val totalSeconds: Int
)

data class DoorState(
    val configured: Boolean,
    val online: Boolean,
    val updatingFirmware: Boolean,
    val lastOkMillis: Long?,
    val consecutiveFailures: Int,
    val lastError: String?,
    val ipAddress: String?,
    val rssiDbm: Int?,
    val uptimeSeconds: Long?,
    val firmwareVersion: String?,
    val isDoorOpen: Boolean,
    val openSecondsRemaining: Int,
    val isRfidEnabled: Boolean,
    val configuredOpenSeconds: Int,
    val isConfigSynced: Boolean,
    val enroll: EnrollState,
    val cards: List<RfidCard>,
    val eventLog: List<DoorEventEntry>
)

data class RegisteredMobileDevice(
    val deviceId: String,
    val deviceName: String,
    val androidVersion: String,
    val appVersion: String,
    val publicKeyFingerprint: String,
    val registeredAtMillis: Long,
    val lastSeenMillis: Long?,
    val status: String, // "ACTIVE" | "REVOKED"
    val isCurrentDevice: Boolean
)
