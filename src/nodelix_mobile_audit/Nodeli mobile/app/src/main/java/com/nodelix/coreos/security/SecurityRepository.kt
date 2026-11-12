package com.nodelix.coreos.security

import android.os.Build
import com.nodelix.coreos.core.AppConfig
import com.nodelix.coreos.core.AppError
import com.nodelix.coreos.core.AppResult
import com.nodelix.coreos.data.local.DiagnosticLogStore
import com.nodelix.coreos.data.local.PreferencesDataStore
import com.nodelix.coreos.data.model.LoginRequestDto
import com.nodelix.coreos.data.model.MobileChallengeRequestDto
import com.nodelix.coreos.data.model.MobileRegisterRequestDto
import com.nodelix.coreos.data.model.MobileVerifyRequestDto
import com.nodelix.coreos.data.model.OtpVerifyRequestDto
import com.nodelix.coreos.data.model.ResetPasswordRequestDto
import com.nodelix.coreos.domain.model.AuthMode
import com.nodelix.coreos.domain.model.RegisteredMobileDevice
import com.nodelix.coreos.domain.model.ServerProfile
import com.nodelix.coreos.domain.model.SessionState
import com.nodelix.coreos.domain.repository.PendingChallenge
import com.nodelix.coreos.domain.repository.SecurityAuthRepository
import com.nodelix.coreos.network.ApiClient
import com.nodelix.coreos.network.NetworkResultMapper
import com.nodelix.coreos.network.WebSocketManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.security.Signature
import java.util.Base64

/**
 * Dual-Mode Security Repository implementing Part 6 specifications:
 * 1. Primary Contract (`/api/mobile/auth/...`): ECDSA P-256 hardware-backed Keystore registration,
 *    32-byte server nonce challenge, canonical domain-separated `SHA256withECDSA` signature,
 *    opaque Bearer session token, and device revocation management.
 * 2. Live Compatibility Mode (`NodelixcoreOS` v1.1.0 `/api/...`): Seamless fallback when `/api/mobile/...`
 *    returns HTTP 404 on an unmodified server, using `/api/login` (`sid` cookie) vaulted in
 *    AES-256-GCM Keystore and gated by hardware ECDSA biometric signing on device unlock.
 */
class SecurityRepository(
    private val apiClient: ApiClient,
    private val keyStoreManager: KeyStoreManager,
    private val sessionManager: SessionManager,
    private val preferencesDataStore: PreferencesDataStore,
    private val webSocketManager: WebSocketManager,
    private val logStore: DiagnosticLogStore,
    private val json: Json,
    private val activeProfileProvider: () -> ServerProfile?,
    private val onProfileDeviceRegistered: suspend (ServerProfile, String) -> Unit
) : SecurityAuthRepository {

    override val sessionState: StateFlow<SessionState> = sessionManager.sessionState

    /**
     * Serializes `POST /api/mobile/auth/refresh` rotations. The backend revokes the old
     * token immediately when it issues a new one, so two concurrent rotations would make
     * the slower request fail with `401 invalid_token` — and any caller that treated that
     * as "session expired" would log out a perfectly valid session.
     */
    private val bearerRefreshMutex = Mutex()

    override suspend fun loginAdminAndRegisterDevice(
        username: String,
        password: String,
        deviceName: String,
        enableBiometricKey: Boolean
    ): AppResult<SessionState> {
        val profile = activeProfileProvider()
            ?: return AppResult.Failure(AppError.InvalidEndpoint("Select and configure a server profile first."))
        if (username.isBlank() || password.isBlank()) {
            return AppResult.Failure(AppError.BadRequest("Admin username and password are required."))
        }

        val apiService = when (val svc = apiClient.requireService()) {
            is AppResult.Failure -> return svc
            is AppResult.Success -> svc.data
        }

        val deviceId = preferencesDataStore.getOrCreateInstallationDeviceId()
        var generatedKey: GeneratedDeviceKey? = null
        if (enableBiometricKey) {
            generatedKey = runCatching {
                withContext(Dispatchers.IO) {
                    keyStoreManager.generateDeviceKeyPair(serverId = profile.id, requireBiometricAuth = true)
                }
            }.onFailure { err ->
                logStore.warn("Security", "Biometric ECDSA key generation skipped: ${err.javaClass.simpleName}")
            }.getOrNull()
        }

        // 1. Try native /api/mobile/auth/register if Keystore key was generated
        if (generatedKey != null) {
            try {
                val registerResp = apiService.registerMobileDevice(
                    MobileRegisterRequestDto(
                        username = username.trim(),
                        password = password,
                        deviceId = deviceId,
                        deviceName = deviceName.ifBlank { "${Build.MANUFACTURER} ${Build.MODEL}".trim() },
                        androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                        appVersion = AppConfig.VERSION_NAME,
                        publicKeyPem = generatedKey.publicKeyPem
                    )
                )
                if (registerResp.isSuccessful && registerResp.body()?.isOk == true) {
                    val body = registerResp.body()!!
                    onProfileDeviceRegistered(profile, deviceId)
                    val issuedToken = body.resolvedSessionToken
                    if (!issuedToken.isNullOrBlank()) {
                        withContext(Dispatchers.IO) {
                            sessionManager.establishBearerSession(
                                serverId = profile.id,
                                username = username.trim(),
                                opaqueBearerToken = issuedToken,
                                expiresAtMillis = body.expiresAt ?: (System.currentTimeMillis() + 12 * 3600_000L)
                            )
                        }
                        logStore.info("Security", "Device registered via /api/mobile/auth/register ($deviceId)")
                        webSocketManager.connect(profile)
                        return AppResult.Success(sessionManager.sessionState.value)
                    }
                } else if (registerResp.code() != 404) {
                    // Explicit auth error (401/403/429/409) from /api/mobile/auth/register
                    return NetworkResultMapper.mapResponse(registerResp, json).let {
                        when (it) {
                            is AppResult.Failure -> it
                            is AppResult.Success -> AppResult.Failure(AppError.AuthRequired("Registration rejected."))
                        }
                    }
                }
            } catch (e: Exception) {
                return NetworkResultMapper.mapThrowable(e, json, logStore, "Security")
            }
        }

        // 2. Fallback / Standard login on NodelixcoreOS v1.1.0 (`POST /api/login` -> `Set-Cookie: sid=...`)
        return try {
            val loginResp = apiService.loginLegacy(
                LoginRequestDto(username = username.trim(), password = password)
            )
            when (val mapped = NetworkResultMapper.mapResponse(loginResp, json)) {
                is AppResult.Failure -> mapped
                is AppResult.Success -> {
                    val setCookies = loginResp.headers().values("Set-Cookie")
                    val sid = setCookies.firstNotNullOfOrNull { header ->
                        Regex("""(?:^|;\s*)sid=([a-fA-F0-9]+)""").find(header)?.groupValues?.get(1)
                    }
                    if (sid.isNullOrBlank()) {
                        return AppResult.Failure(
                            AppError.AuthRequired("Server accepted credentials but did not issue a session token.")
                        )
                    }
                    if (generatedKey != null) {
                        onProfileDeviceRegistered(profile, deviceId)
                    }
                    withContext(Dispatchers.IO) {
                        sessionManager.establishCookieSession(
                            serverId = profile.id,
                            username = username.trim(),
                            sidCookieValue = sid
                        )
                    }
                    logStore.info("Security", "Authenticated admin session established for ${profile.name}")
                    webSocketManager.connect(profile)
                    AppResult.Success(sessionManager.sessionState.value)
                }
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "Security")
        }
    }

    override suspend fun prepareBiometricChallenge(): AppResult<PendingChallenge> {
        val profile = activeProfileProvider()
            ?: return AppResult.Failure(AppError.InvalidEndpoint("No active server configured."))

        val signatureObj = when (val keyOutcome = withContext(Dispatchers.IO) {
            keyStoreManager.initSignatureForSigning(profile.id)
        }) {
            is KeyInitOutcome.Ready -> keyOutcome.signature
            KeyInitOutcome.KeyInvalidated -> return AppResult.Failure(AppError.KeyInvalidated())
            KeyInitOutcome.KeyNotFound -> return AppResult.Failure(
                AppError.AuthRequired("Device biometric key is not registered yet. Sign in with Admin Password first.")
            )
            is KeyInitOutcome.Error -> return AppResult.Failure(AppError.BiometricUnavailable(keyOutcome.reason))
        }

        val apiService = when (val svc = apiClient.requireService()) {
            is AppResult.Failure -> return svc
            is AppResult.Success -> svc.data
        }
        val deviceId = preferencesDataStore.getOrCreateInstallationDeviceId()
        val origin = profile.baseUrl().trimEnd('/')

        return try {
            val challengeResp = apiService.requestMobileChallenge(MobileChallengeRequestDto(deviceId = deviceId))
            if (challengeResp.isSuccessful && challengeResp.body() != null) {
                val body = challengeResp.body()!!
                val now = System.currentTimeMillis()
                if (!SignatureManager.isChallengeValid(body.nonce, body.expiresAt, now)) {
                    return AppResult.Failure(
                        AppError.InvalidSignature("Server issued an expired or weak challenge nonce.")
                    )
                }
                val payload = SignatureManager.buildCanonicalChallengePayload(
                    deviceId = deviceId,
                    challengeId = body.challengeId,
                    nonceBase64 = body.nonce,
                    timestampMillis = now,
                    serverOrigin = origin
                )
                AppResult.Success(
                    PendingChallenge(
                        challengeId = body.challengeId,
                        nonceBase64 = body.nonce,
                        serverOrigin = origin,
                        deviceId = deviceId,
                        timestampMillis = now,
                        expiresAtMillis = body.expiresAt,
                        signatureObject = signatureObj,
                        canonicalPayload = payload,
                        isLegacyLocalUnlock = false
                    )
                )
            } else if (challengeResp.code() == 404) {
                // Unmodified NodelixcoreOS v1.1.0 compatibility: verify vaulted encrypted session exists
                if (!sessionManager.hasVaultedValidSession(profile.id)) {
                    return AppResult.Failure(
                        AppError.AuthRequired("Saved session expired. Sign in once with Admin Password to renew.")
                    )
                }
                val nonceBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val nonceB64 = Base64.getEncoder().encodeToString(nonceBytes)
                val now = System.currentTimeMillis()
                val challengeId = "local-${now}"
                val payload = SignatureManager.buildCanonicalChallengePayload(
                    deviceId = deviceId,
                    challengeId = challengeId,
                    nonceBase64 = nonceB64,
                    timestampMillis = now,
                    serverOrigin = origin
                )
                AppResult.Success(
                    PendingChallenge(
                        challengeId = challengeId,
                        nonceBase64 = nonceB64,
                        serverOrigin = origin,
                        deviceId = deviceId,
                        timestampMillis = now,
                        expiresAtMillis = now + 60_000L,
                        signatureObject = signatureObj,
                        canonicalPayload = payload,
                        isLegacyLocalUnlock = true
                    )
                )
            } else {
                NetworkResultMapper.mapResponse(challengeResp, json).let {
                    when (it) {
                        is AppResult.Failure -> it
                        is AppResult.Success -> AppResult.Failure(AppError.AuthRequired())
                    }
                }
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "Security")
        }
    }

    override suspend fun completeBiometricChallenge(
        challenge: PendingChallenge,
        signedSignature: Signature
    ): AppResult<SessionState> {
        val profile = activeProfileProvider()
            ?: return AppResult.Failure(AppError.InvalidEndpoint("No active server configured."))

        if (System.currentTimeMillis() >= challenge.expiresAtMillis) {
            return AppResult.Failure(AppError.AuthRequired("Authentication challenge expired (60s TTL). Try again."))
        }

        val signatureBase64 = try {
            withContext(Dispatchers.IO) {
                SignatureManager.signPayloadBase64(signedSignature, challenge.canonicalPayload)
            }
        } catch (e: Exception) {
            return AppResult.Failure(AppError.InvalidSignature("Hardware ECDSA signing failed."))
        }

        val apiService = when (val svc = apiClient.requireService()) {
            is AppResult.Failure -> return svc
            is AppResult.Success -> svc.data
        }

        if (challenge.isLegacyLocalUnlock) {
            val signatureValid: Boolean = (
                withContext(Dispatchers.IO) {
                    val pubKey = keyStoreManager.getPublicKey(profile.id) ?: return@withContext null
                    SignatureManager.verifySignatureBase64(pubKey, challenge.canonicalPayload, signatureBase64)
                }
            ) ?: return AppResult.Failure(AppError.KeyInvalidated())
            if (!signatureValid) {
                return AppResult.Failure(AppError.InvalidSignature("Local ECDSA signature verification failed."))
            }
            val unlocked = (
                withContext(Dispatchers.IO) { sessionManager.unlockPersistedSession(profile.id) }
            ) ?: return AppResult.Failure(AppError.AuthRequired("Stored session expired. Please sign in with password."))

            // Confirm the session cookie is still active on the NodelixcoreOS server
            return try {
                val sessCheck = apiService.getLegacySession()
                if (sessCheck.isSuccessful && sessCheck.body()?.authenticated == true) {
                    logStore.info("Security", "Biometric unlock verified against ${profile.name}")
                    webSocketManager.connect(profile)
                    AppResult.Success(unlocked)
                } else {
                    sessionManager.clearSession(profile.id)
                    AppResult.Failure(AppError.AuthRequired("Server session expired or restarted. Sign in with Admin Password."))
                }
            } catch (e: Exception) {
                NetworkResultMapper.mapThrowable(e, json, logStore, "Security")
            }
        }

        return try {
            val verifyResp = apiService.verifyMobileChallenge(
                MobileVerifyRequestDto(
                    deviceId = challenge.deviceId,
                    challengeId = challenge.challengeId,
                    timestamp = challenge.timestampMillis,
                    signatureBase64 = signatureBase64,
                    serverOrigin = challenge.serverOrigin
                )
            )
            when (val mapped = NetworkResultMapper.mapResponse(verifyResp, json)) {
                is AppResult.Failure -> mapped
                is AppResult.Success -> {
                    val data = mapped.data
                    val issuedToken = data.resolvedSessionToken
                    if (!data.isOk || issuedToken.isNullOrBlank()) {
                        return AppResult.Failure(AppError.InvalidSignature(data.message ?: "Challenge signature rejected."))
                    }
                    withContext(Dispatchers.IO) {
                        sessionManager.establishBearerSession(
                            serverId = profile.id,
                            username = data.username,
                            opaqueBearerToken = issuedToken,
                            expiresAtMillis = data.expiresAt
                        )
                    }
                    logStore.info("Security", "ECDSA challenge-response verified with ${profile.name}")
                    webSocketManager.connect(profile)
                    AppResult.Success(sessionManager.sessionState.value)
                }
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "Security")
        }
    }

    /**
     * Rotates the active Bearer session via `POST /api/mobile/auth/refresh`.
     * The server revokes the old token and issues a fresh `sessionToken`.
     * Returns failure (without clearing state) when there is no Bearer session
     * or the server rejects the rotation (caller must re-authenticate instead).
     */
    override suspend fun refreshBearerSession(): AppResult<SessionState> {
        val tokenAtEntry = sessionManager.peekBearerToken()
        return bearerRefreshMutex.withLock {
            val tokenNow = sessionManager.peekBearerToken()
            if (tokenAtEntry != null && tokenNow != null && tokenNow != tokenAtEntry) {
                // A concurrent caller already rotated the session while this one waited
                // for the lock: keep the fresh token instead of revoking it again.
                return@withLock AppResult.Success(sessionManager.sessionState.value)
            }
            rotateBearerSessionLocked()
        }
    }

    private suspend fun rotateBearerSessionLocked(): AppResult<SessionState> {
        val profile = activeProfileProvider()
            ?: return AppResult.Failure(AppError.InvalidEndpoint("No active server configured."))
        val current = sessionManager.sessionState.value
        if (!current.isAuthenticated || current.authMode != AuthMode.MOBILE_ECDSA_BEARER) {
            return AppResult.Failure(AppError.AuthRequired("No active Bearer session to refresh."))
        }
        val apiService = when (val svc = apiClient.requireService()) {
            is AppResult.Failure -> return svc
            is AppResult.Success -> svc.data
        }
        return try {
            val resp = apiService.refreshMobileSession()
            when (val mapped = NetworkResultMapper.mapResponse(resp, json)) {
                is AppResult.Failure -> mapped
                is AppResult.Success -> {
                    val data = mapped.data
                    val rotated = data.resolvedSessionToken
                    if (!data.isOk || rotated.isNullOrBlank()) {
                        return AppResult.Failure(
                            AppError.AuthRequired(data.message ?: "Session refresh rejected by server.")
                        )
                    }
                    withContext(Dispatchers.IO) {
                        sessionManager.establishBearerSession(
                            serverId = profile.id,
                            username = data.username.ifBlank { current.username ?: "admin" },
                            opaqueBearerToken = rotated,
                            expiresAtMillis = data.expiresAt
                        )
                    }
                    logStore.info("Security", "Bearer session rotated via /api/mobile/auth/refresh")
                    AppResult.Success(sessionManager.sessionState.value)
                }
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "Security")
        }
    }

    override suspend fun verifyActiveSession(): AppResult<Boolean> {
        val current = sessionManager.sessionState.value
        if (!current.isAuthenticated) return AppResult.Success(false)

        val apiService = when (val svc = apiClient.requireService()) {
            is AppResult.Failure -> return svc
            is AppResult.Success -> svc.data
        }

        return try {
            if (current.authMode == AuthMode.MOBILE_ECDSA_BEARER) {
                // Proactively rotate sessions that are close to expiry (server TTL 720h).
                if (sessionManager.needsBearerRefresh()) {
                    val refreshed = refreshBearerSession()
                    if (refreshed is AppResult.Success) return AppResult.Success(true)
                    // Refresh failed: fall through to authoritative session check below,
                    // which decides between still-valid, revoked, or expired.
                }
                val resp = apiService.getMobileSession()
                if (resp.isSuccessful && resp.body()?.authenticated == true) {
                    if (resp.body()?.deviceStatus.equals("REVOKED", ignoreCase = true)) {
                        sessionManager.onUnauthorizedOrExpired()
                        return AppResult.Failure(AppError.DeviceRevoked())
                    }
                    AppResult.Success(true)
                } else if (resp.code() == 401) {
                    // Token rejected: attempt exactly one rotation before giving up, so a
                    // server-side restart/expiry does not force an unnecessary full login
                    // when rotation is still possible.
                    val refreshed = refreshBearerSession()
                    if (refreshed is AppResult.Success) {
                        AppResult.Success(true)
                    } else {
                        sessionManager.onUnauthorizedOrExpired()
                        AppResult.Success(false)
                    }
                } else {
                    sessionManager.onUnauthorizedOrExpired()
                    AppResult.Success(false)
                }
            } else {
                val resp = apiService.getLegacySession()
                val ok = resp.isSuccessful && resp.body()?.authenticated == true
                if (!ok) sessionManager.onUnauthorizedOrExpired()
                AppResult.Success(ok)
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "Security")
        }
    }

    override suspend fun logout(): AppResult<Unit> {
        val profile = activeProfileProvider()
        val mode = sessionManager.sessionState.value.authMode
        webSocketManager.disconnect()

        val svcResult = apiClient.requireService()
        if (svcResult is AppResult.Success) {
            runCatching {
                if (mode == AuthMode.MOBILE_ECDSA_BEARER) {
                    svcResult.data.logoutMobile()
                } else {
                    svcResult.data.logoutLegacy()
                }
            }
        }
        withContext(Dispatchers.IO) { sessionManager.clearSession(profile?.id) }
        logStore.info("Security", "Session logged out and local credentials cleared.")
        return AppResult.Success(Unit)
    }

    override suspend fun requestPasswordResetOtp(): AppResult<String> {
        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> return r
            is AppResult.Success -> r.data
        }
        return try {
            when (val res = NetworkResultMapper.mapResponse(svc.requestOtp(), json)) {
                is AppResult.Failure -> res
                is AppResult.Success -> AppResult.Success(res.data.message ?: "OTP sent to admin Telegram.")
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "Security")
        }
    }

    override suspend fun verifyPasswordResetOtp(code: String): AppResult<String> {
        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> return r
            is AppResult.Success -> r.data
        }
        return try {
            when (val res = NetworkResultMapper.mapResponse(svc.verifyOtp(OtpVerifyRequestDto(code.trim())), json)) {
                is AppResult.Failure -> res
                is AppResult.Success -> {
                    val tok = res.data.token
                    if (tok.isNullOrBlank()) {
                        AppResult.Failure(AppError.BadRequest(res.data.message ?: "OTP verification failed."))
                    } else {
                        AppResult.Success(tok)
                    }
                }
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "Security")
        }
    }

    override suspend fun resetAdminPassword(
        token: String,
        newPassword: String,
        confirmPassword: String
    ): AppResult<String> {
        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> return r
            is AppResult.Success -> r.data
        }
        return try {
            val resp = svc.resetPassword(
                ResetPasswordRequestDto(
                    newPassword = newPassword,
                    confirmPassword = confirmPassword,
                    token = token
                )
            )
            when (val res = NetworkResultMapper.mapResponse(resp, json)) {
                is AppResult.Failure -> res
                is AppResult.Success -> AppResult.Success(res.data.message ?: "Admin password updated.")
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "Security")
        }
    }

    override suspend fun getRegisteredDevices(): AppResult<List<RegisteredMobileDevice>> {
        val profile = activeProfileProvider()
            ?: return AppResult.Failure(AppError.InvalidEndpoint("No active server profile."))
        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> return r
            is AppResult.Success -> r.data
        }
        val currentDeviceId = preferencesDataStore.getOrCreateInstallationDeviceId()

        return try {
            val resp = svc.getRegisteredMobileDevices()
            if (resp.code() == 404) {
                // Backend `/api/mobile/devices/registered` not installed yet — report local registration status honestly
                val pubKey = keyStoreManager.getPublicKey(profile.id)
                val localList = if (pubKey != null) {
                    listOf(
                        RegisteredMobileDevice(
                            deviceId = currentDeviceId,
                            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim() + " (Local Keystore)",
                            androidVersion = "Android ${Build.VERSION.RELEASE}",
                            appVersion = AppConfig.VERSION_NAME,
                            publicKeyFingerprint = KeyStoreManager.sha256Hex(pubKey.encoded).take(24),
                            registeredAtMillis = profile.lastConnectedAt ?: System.currentTimeMillis(),
                            lastSeenMillis = System.currentTimeMillis(),
                            status = "LOCAL_COMPAT_MODE",
                            isCurrentDevice = true
                        )
                    )
                } else emptyList()
                return AppResult.Success(localList)
            }
            when (val mapped = NetworkResultMapper.mapResponse(resp, json) { sessionManager.onUnauthorizedOrExpired() }) {
                is AppResult.Failure -> mapped
                is AppResult.Success -> {
                    val items = mapped.data.devices.map { d ->
                        RegisteredMobileDevice(
                            deviceId = d.deviceId,
                            deviceName = d.deviceName,
                            androidVersion = d.androidVersion,
                            appVersion = d.appVersion,
                            publicKeyFingerprint = d.publicKeyFingerprint,
                            registeredAtMillis = d.registeredAt,
                            lastSeenMillis = d.lastSeenAt,
                            status = d.status,
                            isCurrentDevice = d.isCurrentDevice ?: (d.deviceId == currentDeviceId)
                        )
                    }
                    AppResult.Success(items)
                }
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "Security") { sessionManager.onUnauthorizedOrExpired() }
        }
    }

    override suspend fun revokeRegisteredDevice(deviceId: String): AppResult<Unit> {
        val profile = activeProfileProvider()
            ?: return AppResult.Failure(AppError.InvalidEndpoint("No active server profile."))
        val currentDeviceId = preferencesDataStore.getOrCreateInstallationDeviceId()
        val svc = when (val r = apiClient.requireService()) {
            is AppResult.Failure -> return r
            is AppResult.Success -> r.data
        }

        return try {
            val resp = svc.revokeRegisteredMobileDevice(deviceId)
            if (resp.code() == 404) {
                if (deviceId == currentDeviceId) {
                    withContext(Dispatchers.IO) {
                        keyStoreManager.deleteDeviceKey(profile.id)
                        sessionManager.clearSession(profile.id)
                    }
                    webSocketManager.disconnect()
                    return AppResult.Success(Unit)
                }
                return AppResult.Failure(
                    AppError.NotFound("Remote device revocation requires `/api/mobile/devices/registered/:id` on backend.")
                )
            }
            when (val mapped = NetworkResultMapper.mapResponse(resp, json) { sessionManager.onUnauthorizedOrExpired() }) {
                is AppResult.Failure -> mapped
                is AppResult.Success -> {
                    if (deviceId == currentDeviceId) {
                        withContext(Dispatchers.IO) {
                            keyStoreManager.deleteDeviceKey(profile.id)
                            sessionManager.clearSession(profile.id)
                        }
                        webSocketManager.disconnect()
                    }
                    logStore.info("Security", "Revoked mobile device registration: $deviceId")
                    AppResult.Success(Unit)
                }
            }
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "Security") { sessionManager.onUnauthorizedOrExpired() }
        }
    }
}
