package com.nodelix.coreos.core

import android.content.Context
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import com.nodelix.coreos.data.local.DiagnosticLogStore
import com.nodelix.coreos.data.local.PreferencesDataStore
import com.nodelix.coreos.data.repository.DeviceControlRepositoryImpl
import com.nodelix.coreos.data.repository.DiagnosticsRepositoryImpl
import com.nodelix.coreos.data.repository.NotificationCenterRepositoryImpl
import com.nodelix.coreos.data.repository.ServerProfileRepositoryImpl
import com.nodelix.coreos.data.repository.ServerSettingsRepositoryImpl
import com.nodelix.coreos.data.repository.StorageManagerRepositoryImpl
import com.nodelix.coreos.data.repository.SystemMonitorRepositoryImpl
import com.nodelix.coreos.data.repository.UpdateCenterRepositoryImpl
import com.nodelix.coreos.domain.repository.DeviceControlRepository
import com.nodelix.coreos.domain.repository.DiagnosticsRepository
import com.nodelix.coreos.domain.repository.NotificationCenterRepository
import com.nodelix.coreos.domain.repository.ServerProfileRepository
import com.nodelix.coreos.domain.repository.ServerSettingsRepository
import com.nodelix.coreos.domain.repository.StorageManagerRepository
import com.nodelix.coreos.domain.repository.SystemMonitorRepository
import com.nodelix.coreos.domain.repository.UpdateCenterRepository
import com.nodelix.coreos.domain.usecase.ConnectServerUseCase
import com.nodelix.coreos.domain.usecase.ControlDoorUseCase
import com.nodelix.coreos.domain.usecase.ControlRelayUseCase
import com.nodelix.coreos.domain.usecase.ValidateAndSaveServerUseCase
import com.nodelix.coreos.network.ApiClient
import com.nodelix.coreos.network.NetworkMonitor
import com.nodelix.coreos.network.WebSocketManager
import com.nodelix.coreos.security.KeyStoreManager
import com.nodelix.coreos.security.NodelixBiometricManager
import com.nodelix.coreos.security.SecurityRepository
import com.nodelix.coreos.security.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.Call

/**
 * Application-scoped dependency injection container following Clean Architecture
 * and MVVM Repository Pattern without reflection overhead.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Volatile
    private var autoReconnectWebSocketEnabled: Boolean = true

    // Local & Logging
    val logStore: DiagnosticLogStore = DiagnosticLogStore()
    val preferencesDataStore: PreferencesDataStore = PreferencesDataStore(appContext, json)

    // Security & Hardware Keystore
    val keyStoreManager: KeyStoreManager = KeyStoreManager()
    val biometricManager: NodelixBiometricManager = NodelixBiometricManager(appContext)
    val sessionManager: SessionManager = SessionManager(
        context = appContext,
        keyStoreManager = keyStoreManager,
        json = json
    )

    // Network: HTTP-only LAN transport
    val networkMonitor: NetworkMonitor = NetworkMonitor(appContext)

    // Deferred cross-reference holders resolved immediately in init order
    lateinit var apiClient: ApiClient
        private set
    lateinit var webSocketManager: WebSocketManager
        private set

    val serverRepositoryImpl: ServerProfileRepositoryImpl = ServerProfileRepositoryImpl(
        scope = appScope,
        preferencesDataStore = preferencesDataStore,
        sessionManager = sessionManager,
        logStore = logStore,
        json = json,
        apiClientProvider = { apiClient },
        webSocketManagerProvider = { webSocketManager }
    )

    val serverRepository: ServerProfileRepository = serverRepositoryImpl

    init {
        apiClient = ApiClient(
            json = json,
            sessionManager = sessionManager,
            logStore = logStore,
            activeProfileProvider = { serverRepository.activeProfile.value }
        )

        webSocketManager = WebSocketManager(
            scope = appScope,
            json = json,
            apiClient = apiClient,
            sessionManager = sessionManager,
            logStore = logStore,
            autoReconnectAllowed = { autoReconnectWebSocketEnabled }
        )

        appScope.launch {
            preferencesDataStore.appPreferencesFlow.collectLatest { prefs ->
                autoReconnectWebSocketEnabled = prefs.autoReconnectWebSocket
            }
        }

        // Restore the realtime stream when Android regains LAN connectivity (Wi-Fi drop
        // recovery). Guarded by the user auto-reconnect preference, an authenticated
        // session, and the single-connection mutex inside WebSocketManager.
        appScope.launch {
            var wasOnline = true
            networkMonitor.status.collectLatest { net ->
                val regained = !wasOnline && net.isOnline
                wasOnline = net.isOnline
                if (!regained || !autoReconnectWebSocketEnabled) return@collectLatest
                val profile = serverRepository.activeProfile.value ?: return@collectLatest
                if (!sessionManager.sessionState.value.isAuthenticated) return@collectLatest
                if (webSocketManager.state.value == com.nodelix.coreos.network.WsConnectionState.CONNECTED) return@collectLatest
                logStore.info("Connection", "Network regained — restoring realtime WebSocket stream.")
                runCatching { webSocketManager.connect(profile) }
            }
        }
    }

    val securityRepository: SecurityRepository = SecurityRepository(
        apiClient = apiClient,
        keyStoreManager = keyStoreManager,
        sessionManager = sessionManager,
        preferencesDataStore = preferencesDataStore,
        webSocketManager = webSocketManager,
        logStore = logStore,
        json = json,
        activeProfileProvider = { serverRepository.activeProfile.value },
        onProfileDeviceRegistered = { profile, deviceId ->
            serverRepository.saveProfile(
                profile.copy(
                    registeredDeviceId = deviceId,
                    deviceKeyRegistered = true
                )
            )
        }
    )

    val notificationRepository: NotificationCenterRepository = NotificationCenterRepositoryImpl(
        scope = appScope,
        webSocketManagerProvider = { webSocketManager }
    )

    val systemRepository: SystemMonitorRepository = SystemMonitorRepositoryImpl(
        scope = appScope,
        apiClient = apiClient,
        webSocketManager = webSocketManager,
        sessionManager = sessionManager,
        preferencesDataStore = preferencesDataStore,
        logStore = logStore,
        json = json
    )

    val deviceRepository: DeviceControlRepository = DeviceControlRepositoryImpl(
        scope = appScope,
        apiClient = apiClient,
        webSocketManager = webSocketManager,
        sessionManager = sessionManager,
        notificationRepository = notificationRepository,
        logStore = logStore,
        json = json
    )

    val storageRepository: StorageManagerRepository = StorageManagerRepositoryImpl(
        appContext = appContext,
        apiClient = apiClient,
        sessionManager = sessionManager,
        notificationRepository = notificationRepository,
        logStore = logStore,
        json = json,
        activeProfileProvider = { serverRepository.activeProfile.value }
    )

    val updateRepository: UpdateCenterRepository = UpdateCenterRepositoryImpl(
        scope = appScope,
        appContext = appContext,
        apiClient = apiClient,
        webSocketManager = webSocketManager,
        sessionManager = sessionManager,
        logStore = logStore,
        json = json
    )

    val serverSettingsRepository: ServerSettingsRepository = ServerSettingsRepositoryImpl(
        apiClient = apiClient,
        sessionManager = sessionManager,
        logStore = logStore,
        json = json
    )

    val diagnosticsRepository: DiagnosticsRepository = DiagnosticsRepositoryImpl(
        serverRepository = serverRepository,
        systemRepository = systemRepository,
        deviceRepository = deviceRepository,
        sessionManager = sessionManager,
        webSocketManager = webSocketManager,
        networkMonitor = networkMonitor,
        preferencesDataStore = preferencesDataStore,
        logStore = logStore
    )

    // Domain UseCases
    val validateAndSaveServerUseCase = ValidateAndSaveServerUseCase(serverRepository)
    val connectServerUseCase = ConnectServerUseCase(serverRepository)
    val controlDoorUseCase = ControlDoorUseCase(deviceRepository)
    val controlRelayUseCase = ControlRelayUseCase(deviceRepository)

    private val dynamicCoilLoader: ImageLoader by lazy {
        ImageLoader.Builder(appContext)
            .callFactory(Call.Factory { request -> apiClient.currentOkHttpClient().newCall(request) })
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }

    /**
     * Returns an [ImageLoader] whose underlying OkHttp call factory dynamically delegates
     * to [ApiClient.currentOkHttpClient], preserving session auth headers.
     */
    fun coilImageLoader(): ImageLoader = dynamicCoilLoader
}
