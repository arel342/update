package com.nodelix.coreos.security

import android.content.Context
import com.nodelix.coreos.domain.model.AuthMode
import com.nodelix.coreos.domain.model.SessionState
import com.nodelix.coreos.util.Constants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class StoredEncryptedSession(
    val serverId: String,
    val username: String,
    val authMode: String,
    val encryptedToken: String,
    val expiresAtMillis: Long,
    val vaultedVaultToken: Boolean = false
)

/**
 * Manages authenticated session credentials in memory and Keystore-encrypted local storage.
 * Opaque Bearer tokens and `sid` session cookies are NEVER written to logs or exposed in URLs.
 */
class SessionManager(
    context: Context,
    private val keyStoreManager: KeyStoreManager,
    private val json: Json,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val prefs = context.getSharedPreferences("nodelix_secure_session", Context.MODE_PRIVATE)

    @Volatile
    private var activeBearerToken: String? = null

    @Volatile
    private var activeSessionCookieSid: String? = null

    private val _sessionState = MutableStateFlow(
        SessionState(
            isAuthenticated = false,
            serverId = null,
            username = null,
            authMode = null,
            expiresAtMillis = null,
            deviceRegistered = false
        )
    )
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    fun establishBearerSession(
        serverId: String,
        username: String,
        opaqueBearerToken: String,
        expiresAtMillis: Long = clock() + Constants.DEFAULT_SESSION_TTL_MS
    ) {
        activeBearerToken = opaqueBearerToken
        activeSessionCookieSid = null
        persistEncrypted(
            serverId = serverId,
            username = username,
            mode = AuthMode.MOBILE_ECDSA_BEARER,
            secret = opaqueBearerToken,
            expiresAtMillis = expiresAtMillis
        )
        _sessionState.value = SessionState(
            isAuthenticated = true,
            serverId = serverId,
            username = username,
            authMode = AuthMode.MOBILE_ECDSA_BEARER,
            expiresAtMillis = expiresAtMillis,
            deviceRegistered = keyStoreManager.hasValidDeviceKey(serverId)
        )
    }

    fun establishCookieSession(
        serverId: String,
        username: String,
        sidCookieValue: String,
        expiresAtMillis: Long = clock() + Constants.DEFAULT_SESSION_TTL_MS
    ) {
        activeSessionCookieSid = sidCookieValue
        activeBearerToken = null
        persistEncrypted(
            serverId = serverId,
            username = username,
            mode = AuthMode.LEGACY_COOKIE_BIOMETRIC,
            secret = sidCookieValue,
            expiresAtMillis = expiresAtMillis
        )
        _sessionState.value = SessionState(
            isAuthenticated = true,
            serverId = serverId,
            username = username,
            authMode = AuthMode.LEGACY_COOKIE_BIOMETRIC,
            expiresAtMillis = expiresAtMillis,
            deviceRegistered = keyStoreManager.hasValidDeviceKey(serverId)
        )
    }

    /**
     * Captures a rotated `sid` cookie from `Set-Cookie` response headers.
     *
     * Safety guard: this is strictly ignored while a Bearer session is active so a
     * stray cookie can never overwrite (or get attached next to) the persisted
     * `sessionToken` vault for the same server profile.
     */
    fun updateCookieFromResponse(serverId: String, sidCookieValue: String) {
        if (sidCookieValue.isBlank()) return
        val current = _sessionState.value
        if (current.isAuthenticated &&
            current.authMode == AuthMode.MOBILE_ECDSA_BEARER &&
            activeBearerToken != null
        ) {
            return
        }
        activeSessionCookieSid = sidCookieValue
        val expires = current.expiresAtMillis ?: (clock() + Constants.DEFAULT_SESSION_TTL_MS)
        persistEncrypted(
            serverId = serverId,
            username = current.username ?: "admin",
            mode = AuthMode.LEGACY_COOKIE_BIOMETRIC,
            secret = sidCookieValue,
            expiresAtMillis = expires
        )
    }

    /**
     * True when the active Bearer session should be proactively rotated via
     * `POST /api/mobile/auth/refresh` (expiry approaching within [graceBeforeExpiryMs]).
     */
    fun needsBearerRefresh(graceBeforeExpiryMs: Long = Constants.BEARER_REFRESH_GRACE_MS): Boolean {
        val state = _sessionState.value
        if (!state.isAuthenticated || state.authMode != AuthMode.MOBILE_ECDSA_BEARER) return false
        if (activeBearerToken.isNullOrBlank()) return false
        val expires = state.expiresAtMillis ?: return false
        return expires - clock() <= graceBeforeExpiryMs
    }

    /**
     * Restores persisted session metadata during boot.
     *
     * Security invariant: credentials are NEVER silently restored on a cold start.
     * The vault key lives in Android Keystore without a per-use user-authentication
     * binding, so decrypting it without an explicit user gesture would be an
     * authentication downgrade. Callers that want the vaulted credential must either
     * unlock it through `BiometricPrompt` (`SecurityRepository`) or pass
     * [autoUnlockSession] = true explicitly for a deliberate, already-authenticated flow.
     */
    fun restorePermittedSession(serverId: String, autoUnlockSession: Boolean = false): SessionState {
        val deviceHasKey = keyStoreManager.hasValidDeviceKey(serverId)
        val raw = prefs.getString(sessionKeyFor(serverId), null)
        if (raw.isNullOrBlank()) {
            val unauthed = SessionState(false, serverId, null, null, null, deviceHasKey)
            _sessionState.value = unauthed
            return unauthed
        }

        val stored = runCatching { json.decodeFromString<StoredEncryptedSession>(raw) }.getOrNull()
        if (stored == null || stored.expiresAtMillis <= clock()) {
            clearSession(serverId)
            val expired = SessionState(false, serverId, null, null, null, deviceHasKey)
            _sessionState.value = expired
            return expired
        }

        if (!autoUnlockSession) {
            // Keep the vault, but require biometric/password authentication on cold start.
            activeBearerToken = null
            activeSessionCookieSid = null
            val lockedState = SessionState(
                isAuthenticated = false,
                serverId = serverId,
                username = stored.username,
                authMode = runCatching { AuthMode.valueOf(stored.authMode) }.getOrNull(),
                expiresAtMillis = stored.expiresAtMillis,
                deviceRegistered = deviceHasKey
            )
            _sessionState.value = lockedState
            return lockedState
        }

        return unlockPersistedSession(serverId) ?: SessionState(false, serverId, null, null, null, deviceHasKey)
    }

    fun hasVaultedValidSession(serverId: String): Boolean {
        val raw = prefs.getString(sessionKeyFor(serverId), null) ?: return false
        val stored = runCatching { json.decodeFromString<StoredEncryptedSession>(raw) }.getOrNull() ?: return false
        return stored.expiresAtMillis > clock()
    }

    fun unlockPersistedSession(serverId: String): SessionState? {
        val raw = prefs.getString(sessionKeyFor(serverId), null) ?: return null
        val stored = runCatching { json.decodeFromString<StoredEncryptedSession>(raw) }.getOrNull() ?: return null
        if (stored.expiresAtMillis <= clock()) {
            clearSession(serverId)
            return null
        }
        val secret = keyStoreManager.decryptSecret(stored.encryptedToken) ?: run {
            clearSession(serverId)
            return null
        }
        val mode = runCatching { AuthMode.valueOf(stored.authMode) }.getOrDefault(AuthMode.LEGACY_COOKIE_BIOMETRIC)
        if (mode == AuthMode.MOBILE_ECDSA_BEARER) {
            activeBearerToken = secret
            activeSessionCookieSid = null
        } else {
            activeSessionCookieSid = secret
            activeBearerToken = null
        }
        val restored = SessionState(
            isAuthenticated = true,
            serverId = serverId,
            username = stored.username,
            authMode = mode,
            expiresAtMillis = stored.expiresAtMillis,
            deviceRegistered = keyStoreManager.hasValidDeviceKey(serverId)
        )
        _sessionState.value = restored
        return restored
    }

    /**
     * Non-mutating read of the in-memory Bearer token.
     * Unlike [currentBearerToken] this never expires the session as a side effect, so
     * token-rotation code can compare "the token as it was" with "the token now".
     */
    fun peekBearerToken(): String? = activeBearerToken

    fun currentBearerToken(): String? {
        val state = _sessionState.value
        val exp = state.expiresAtMillis
        if (!state.isAuthenticated || (exp != null && exp <= clock())) {
            if (state.isAuthenticated) onUnauthorizedOrExpired()
            return null
        }
        return activeBearerToken
    }

    fun currentCookieSid(): String? {
        val state = _sessionState.value
        val exp = state.expiresAtMillis
        if (!state.isAuthenticated || (exp != null && exp <= clock())) {
            if (state.isAuthenticated) onUnauthorizedOrExpired()
            return null
        }
        return activeSessionCookieSid
    }

    fun onUnauthorizedOrExpired() {
        val serverId = _sessionState.value.serverId
        activeBearerToken = null
        activeSessionCookieSid = null
        if (serverId != null) {
            prefs.edit().remove(sessionKeyFor(serverId)).apply()
        }
        _sessionState.value = SessionState(
            isAuthenticated = false,
            serverId = serverId,
            username = null,
            authMode = null,
            expiresAtMillis = null,
            deviceRegistered = serverId?.let { keyStoreManager.hasValidDeviceKey(it) } ?: false
        )
    }

    fun clearSession(serverId: String? = _sessionState.value.serverId) {
        activeBearerToken = null
        activeSessionCookieSid = null
        if (serverId != null) {
            prefs.edit().remove(sessionKeyFor(serverId)).apply()
        }
        _sessionState.value = SessionState(
            isAuthenticated = false,
            serverId = serverId,
            username = null,
            authMode = null,
            expiresAtMillis = null,
            deviceRegistered = serverId?.let { keyStoreManager.hasValidDeviceKey(it) } ?: false
        )
    }

    private fun persistEncrypted(
        serverId: String,
        username: String,
        mode: AuthMode,
        secret: String,
        expiresAtMillis: Long
    ) {
        runCatching {
            val encrypted = keyStoreManager.encryptSecret(secret)
            val payload = json.encodeToString(
                StoredEncryptedSession(
                    serverId = serverId,
                    username = username,
                    authMode = mode.name,
                    encryptedToken = encrypted,
                    expiresAtMillis = expiresAtMillis,
                    vaultedVaultToken = true
                )
            )
            prefs.edit().putString(sessionKeyFor(serverId), payload).apply()
        }
    }

    private fun sessionKeyFor(serverId: String): String = "enc_session_$serverId"
}
