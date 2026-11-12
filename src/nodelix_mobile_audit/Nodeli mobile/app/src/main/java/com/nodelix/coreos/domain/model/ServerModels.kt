package com.nodelix.coreos.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = ProtocolTypeSerializer::class)
enum class ProtocolType(val scheme: String, val wsScheme: String) {
    HTTP("http", "ws")
}

/** Reads old persisted HTTPS profiles as HTTP so an app upgrade cannot crash on legacy data. */
object ProtocolTypeSerializer : KSerializer<ProtocolType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ProtocolType", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ProtocolType) = encoder.encodeString("HTTP")

    override fun deserialize(decoder: Decoder): ProtocolType = when (decoder.decodeString().uppercase()) {
        "HTTP", "HTTPS" -> ProtocolType.HTTP
        else -> throw SerializationException("Unsupported server protocol; HTTP is the only supported protocol.")
    }
}

enum class ConnectionState {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    ERROR,
    AUTHENTICATION_REQUIRED
}

enum class AuthMode {
    /** Native `/api/mobile/auth/...` ECDSA P-256 challenge-response + opaque Bearer token */
    MOBILE_ECDSA_BEARER,

    /** Compatible mode with existing NodelixcoreOS v1.1.0 `/api/login` (`sid` cookie) + local Keystore biometric gate */
    LEGACY_COOKIE_BIOMETRIC
}

@Serializable
data class ServerProfile(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int,
    val protocol: ProtocolType = ProtocolType.HTTP,
    val timeoutSeconds: Int = 15,
    val description: String = "",
    val lastConnectedAt: Long? = null,
    val lastResponseTimeMs: Long? = null,
    val registeredDeviceId: String? = null,
    val deviceKeyRegistered: Boolean = false
) {
    fun baseUrl(): String {
        val formattedHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host.trim()
        return "${protocol.scheme}://${formattedHost}:$port/"
    }

    fun webSocketUrl(): String {
        val formattedHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host.trim()
        return "${protocol.wsScheme}://${formattedHost}:$port/"
    }
}

data class ConnectionProbeResult(
    val reachable: Boolean,
    val responseTimeMs: Long,
    val authenticated: Boolean,
    val setupRequired: Boolean,
    val mobileAuthSupported: Boolean,
    val serverLanguage: String?,
    val errorMessage: String? = null
)

data class SessionState(
    val isAuthenticated: Boolean,
    val serverId: String?,
    val username: String?,
    val authMode: AuthMode?,
    val expiresAtMillis: Long?,
    val deviceRegistered: Boolean
)
