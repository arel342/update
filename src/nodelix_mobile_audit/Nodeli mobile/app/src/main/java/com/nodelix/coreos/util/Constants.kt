package com.nodelix.coreos.util

object Constants {
    const val APP_NAME = "NodelixcoreOS Mobile"
    const val AUTH_DOMAIN_PREFIX = "NODELIXCOREOS-AUTH-V1"
    const val DEFAULT_PORT = 3000
    const val DEFAULT_TIMEOUT_SECONDS = 15
    const val MIN_TIMEOUT_SECONDS = 3
    const val MAX_TIMEOUT_SECONDS = 120
    const val DEFAULT_SESSION_TTL_MS = 12L * 60L * 60L * 1000L // 12 hours default per spec
    /** Proactively rotate Bearer sessions expiring within 24h (server TTL default 720h). */
    const val BEARER_REFRESH_GRACE_MS = 24L * 60L * 60L * 1000L
    /** Maximum automatic WebSocket reconnect attempts before requiring manual retry. */
    const val WS_MAX_RECONNECT_ATTEMPTS = 15

    // Keystore aliases
    const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    const val ECDSA_KEY_ALIAS_PREFIX = "nodelix_device_ecdsa_"
    const val SESSION_AES_KEY_ALIAS = "nodelix_session_aes_gcm_v1"

    // ESP OTA firmware magic byte (both ESP32 and ESP8266 start with 0xE9)
    const val ESP_FIRMWARE_MAGIC_BYTE: Byte = 0xE9.toByte()
    const val DEFAULT_OTA_MAX_MB = 4

    // Notification channel
    const val ALERT_NOTIFICATION_CHANNEL_ID = "nodelix_system_alerts"

}
