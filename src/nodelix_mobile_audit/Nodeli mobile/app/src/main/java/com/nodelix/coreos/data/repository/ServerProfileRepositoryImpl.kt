package com.nodelix.coreos.data.repository

import com.nodelix.coreos.core.AppError
import com.nodelix.coreos.core.AppResult
import com.nodelix.coreos.data.local.DiagnosticLogStore
import com.nodelix.coreos.data.local.PreferencesDataStore
import com.nodelix.coreos.domain.model.ConnectionProbeResult
import com.nodelix.coreos.domain.model.ConnectionState
import com.nodelix.coreos.domain.model.ServerProfile
import com.nodelix.coreos.domain.repository.ServerProfileRepository
import com.nodelix.coreos.network.ApiClient
import com.nodelix.coreos.network.NetworkResultMapper
import com.nodelix.coreos.network.WebSocketManager
import com.nodelix.coreos.security.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.system.measureTimeMillis

class ServerProfileRepositoryImpl(
    scope: CoroutineScope,
    private val preferencesDataStore: PreferencesDataStore,
    private val sessionManager: SessionManager,
    private val logStore: DiagnosticLogStore,
    private val json: Json,
    private val apiClientProvider: () -> ApiClient,
    private val webSocketManagerProvider: () -> WebSocketManager
) : ServerProfileRepository {

    override val profiles: Flow<List<ServerProfile>> = preferencesDataStore.serverProfilesFlow

    private val _activeProfile = MutableStateFlow<ServerProfile?>(null)
    override val activeProfile: StateFlow<ServerProfile?> = _activeProfile.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    init {
        scope.launch {
            combine(
                preferencesDataStore.serverProfilesFlow,
                preferencesDataStore.appPreferencesFlow
            ) { list, prefs ->
                list.firstOrNull { it.id == prefs.activeServerId } ?: list.firstOrNull()
            }.collect { selected ->
                _activeProfile.value = selected
            }
        }
    }

    override suspend fun saveProfile(profile: ServerProfile): AppResult<ServerProfile> {
        val current = preferencesDataStore.serverProfilesFlow.first().toMutableList()
        val existingIdx = current.indexOfFirst { it.id == profile.id }
        if (existingIdx >= 0) {
            current[existingIdx] = profile
        } else {
            current.add(profile)
        }
        preferencesDataStore.saveServerProfiles(current)
        if (_activeProfile.value == null || _activeProfile.value?.id == profile.id) {
            preferencesDataStore.setActiveServerId(profile.id)
            _activeProfile.value = profile
        }
        logStore.info("Connection", "Saved server profile '${profile.name}' (${profile.host}:${profile.port})")
        return AppResult.Success(profile)
    }

    override suspend fun deleteProfile(profileId: String): AppResult<Unit> {
        val current = preferencesDataStore.serverProfilesFlow.first().toMutableList()
        val wasActive = _activeProfile.value?.id == profileId
        if (wasActive) {
            disconnectActiveServer()
            sessionManager.clearSession(profileId)
        }
        current.removeAll { it.id == profileId }
        preferencesDataStore.saveServerProfiles(current)
        if (wasActive) {
            val nextId = current.firstOrNull()?.id
            preferencesDataStore.setActiveServerId(nextId)
            _activeProfile.value = current.firstOrNull()
        }
        logStore.info("Connection", "Deleted server profile $profileId")
        return AppResult.Success(Unit)
    }

    override suspend fun setActiveProfile(profileId: String): AppResult<ServerProfile> {
        val list = preferencesDataStore.serverProfilesFlow.first()
        val target = list.firstOrNull { it.id == profileId }
            ?: return AppResult.Failure(AppError.NotFound("Server profile not found."))
        if (_activeProfile.value?.id != target.id) {
            webSocketManagerProvider().disconnect()
        }
        preferencesDataStore.setActiveServerId(target.id)
        _activeProfile.value = target
        sessionManager.restorePermittedSession(target.id, autoUnlockSession = false)
        return AppResult.Success(target)
    }

    override suspend fun testConnection(profile: ServerProfile): AppResult<ConnectionProbeResult> {
        val service = when (val s = apiClientProvider().serviceForProfile(profile, cacheAsActive = false)) {
            is AppResult.Failure -> return s
            is AppResult.Success -> s.data
        }
        return probeService(service)
    }

    override suspend fun connectActiveServer(): AppResult<ConnectionProbeResult> {
        val profile = _activeProfile.value
            ?: return AppResult.Failure(AppError.InvalidEndpoint("No active server configured."))

        _connectionState.value = ConnectionState.CONNECTING
        val service = when (val s = apiClientProvider().serviceForProfile(profile, cacheAsActive = true)) {
            is AppResult.Failure -> {
                _connectionState.value = ConnectionState.ERROR
                return s
            }
            is AppResult.Success -> s.data
        }

        return when (val probe = probeService(service)) {
            is AppResult.Failure -> {
                _connectionState.value = if (probe.error is AppError.AuthRequired) {
                    ConnectionState.AUTHENTICATION_REQUIRED
                } else {
                    ConnectionState.ERROR
                }
                probe
            }
            is AppResult.Success -> {
                val now = System.currentTimeMillis()
                val updated = profile.copy(
                    lastConnectedAt = now,
                    lastResponseTimeMs = probe.data.responseTimeMs
                )
                saveProfile(updated)
                if (probe.data.authenticated && sessionManager.sessionState.value.isAuthenticated) {
                    _connectionState.value = ConnectionState.CONNECTED
                    webSocketManagerProvider().connect(updated)
                } else {
                    _connectionState.value = ConnectionState.AUTHENTICATION_REQUIRED
                }
                probe
            }
        }
    }

    override suspend fun disconnectActiveServer() {
        webSocketManagerProvider().disconnect()
        _connectionState.value = ConnectionState.DISCONNECTED
        logStore.info("Connection", "Disconnected from active server.")
    }

    fun markAuthenticatedConnected() {
        _connectionState.value = ConnectionState.CONNECTED
    }

    fun markAuthenticationRequired() {
        _connectionState.value = ConnectionState.AUTHENTICATION_REQUIRED
    }

    private suspend fun probeService(
        service: com.nodelix.coreos.network.ApiService
    ): AppResult<ConnectionProbeResult> {
        return try {
            var langRespCode = 0
            var serverLang: String? = null
            val rttMs = measureTimeMillis {
                val langResp = service.getServerLanguage()
                langRespCode = langResp.code()
                serverLang = langResp.body()?.lang
            }
            if (langRespCode == 409) {
                return AppResult.Success(
                    ConnectionProbeResult(
                        reachable = true,
                        responseTimeMs = rttMs,
                        authenticated = false,
                        setupRequired = true,
                        mobileAuthSupported = false,
                        serverLanguage = serverLang,
                        errorMessage = "Initial setup wizard (/setup.html) has not been completed on server."
                    )
                )
            }

            val mobileSessionResp = runCatching { service.getMobileSession() }.getOrNull()
            val mobileSupported = mobileSessionResp != null && mobileSessionResp.code() != 404
            val legacySessionResp = runCatching { service.getLegacySession() }.getOrNull()
            val isAuthed = (mobileSessionResp?.body()?.authenticated == true) ||
                (legacySessionResp?.body()?.authenticated == true)

            AppResult.Success(
                ConnectionProbeResult(
                    reachable = true,
                    responseTimeMs = rttMs,
                    authenticated = isAuthed,
                    setupRequired = legacySessionResp?.code() == 409,
                    mobileAuthSupported = mobileSupported,
                    serverLanguage = serverLang
                )
            )
        } catch (e: Exception) {
            NetworkResultMapper.mapThrowable(e, json, logStore, "Connection")
        }
    }
}
